package ru.curs.celesta;

import ru.curs.celesta.dbutils.BasicDataAccessor;
import ru.curs.celesta.dbutils.ILoggingManager;
import ru.curs.celesta.dbutils.IPermissionManager;
import ru.curs.celesta.dbutils.adaptors.DBAdaptor;
import ru.curs.celesta.score.Score;

import java.sql.Connection;
import java.sql.SQLException;

import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Call context containing a DB connection carrying a transaction and a user identifier.
 */
public class CallContext implements ICallContext {

    private enum State {
        NEW,
        ACTIVE,
        CLOSED
    }

    /**
     * Maximal number of accessors that can be opened within single context.
     */
    public static final int MAX_DATA_ACCESSORS = 1023;

    private static final Map<Connection, Integer> PIDSCACHE = Collections
            .synchronizedMap(new WeakHashMap<>());

    private final String userId;

    private ICelesta celesta;
    private Connection conn;
    private String procName;

    private int dbPid;
    private Date startTime;
    private long startMonotonicTime;
    private long endMonotonicTime;

    private BasicDataAccessor lastDataAccessor;

    private int dataAccessorsCount;
    private State state;

    /**
     * Creates new not activated context.
     *
     * @param userId User identifier. Cannot be null or empty.
     */
    public CallContext(String userId) {
        if (Objects.requireNonNull(userId).isEmpty()) {
            throw new CelestaException("Call context's user Id must not be empty");
        }
        this.userId = userId;
        state = State.NEW;
    }

    /**
     * Creates activated context.
     *
     * @param userId   User identifier. Cannot be null or empty.
     * @param celesta  Celesta instance.
     * @param procName Procedure which is being called in this context.
     */
    public CallContext(String userId, ICelesta celesta, String procName) {
        this(userId);
        activate(celesta, procName);
    }

    final int getDataAccessorsCount() {
        return dataAccessorsCount;
    }

    /**
     * Activates CallContext with 'live' Celesta and procName.
     *
     * @param celesta  Celesta to use CallContext with.
     * @param procName Name of the called procedure (for logging/audit needs).
     */
    public void activate(ICelesta celesta, String procName) {
        Objects.requireNonNull(celesta);
        Objects.requireNonNull(procName);

        if (state != State.NEW) {
            throw new CelestaException("Cannot activate CallContext in %s state (NEW expected).", state);
        }
        this.celesta = celesta;
        this.procName = procName;
        this.state = State.ACTIVE;
        conn = celesta.getConnectionPool().get();
        dbPid = PIDSCACHE.computeIfAbsent(conn,
                getDbAdaptor()::getDBPid);
        startTime = new Date();
        startMonotonicTime = System.nanoTime();
    }

    /**
     * Active database JDBC connection.
     */
    public Connection getConn() {
        return conn;
    }

    /**
     * Name of the current user.
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Commits the current transaction. Will cause error for not-activated or closed context.
     * <p>
     * Wraps SQLException into CelestaException.
     */
    public void commit() {
        if (state == State.ACTIVE) {
            try {
                conn.commit();
            } catch (SQLException e) {
                throw new CelestaException("Commit unsuccessful: %s", e.getMessage());
            }
        } else {
            throw new CelestaException("Not active context cannot be commited");
        }
    }

    /**
     * Rollbacks the current transaction. Does nothing for not-activated context.
     * <p>
     * Wraps SQLException into CelestaException.
     */
    public void rollback() {
        if (conn != null) {
            try {
                conn.rollback();
            } catch (SQLException e) {
                throw new CelestaException("Rollback unsuccessful: %s", e.getMessage());
            }
        }
    }

    /**
     * Celesta instance. Null for not activated context.
     */
    public ICelesta getCelesta() {
        return celesta;
    }

    /**
     * Score of current Celesta instance.
     */
    public Score getScore() {
        return celesta.getScore();
    }

    public void setLastDataAccessor(BasicDataAccessor dataAccessor) {
        lastDataAccessor = dataAccessor;
    }

    public void incDataAccessorsCount() {
        if (dataAccessorsCount > MAX_DATA_ACCESSORS) {
            throw new CelestaException(
                    "Too many data accessors created in one Celesta procedure call. Check for leaks!"
            );
        }
        dataAccessorsCount++;
    }

    /**
     * Уменьшает счетчик открытых объектов доступа.
     */
    public void decDataAccessorsCount() {
        dataAccessorsCount--;
    }

    /**
     * Получает последний объект доступа.
     */
    public BasicDataAccessor getLastDataAccessor() {
        return lastDataAccessor;
    }

    /**
     * Закрытие всех классов доступа.
     */
    private void closeDataAccessors() {
        while (lastDataAccessor != null) {
            lastDataAccessor.close();
        }
    }

    /**
     * Возвращает Process Id текущего подключения к базе данных.
     */
    public int getDBPid() {
        return dbPid;
    }

    /**
     * Возвращает имя процедуры, которая была изначально вызвана.
     */
    public String getProcName() {
        return procName;
    }

    /**
     * Returns the calendar date of CallContext activation.
     */
    public Date getStartTime() {
        return startTime;
    }

    /**
     * Returns number of nanoseconds since CallContext activation.
     */
    public long getDurationNs() {
        switch (state){
            case NEW: return 0;
            case ACTIVE: return System.nanoTime() - startMonotonicTime;
            default:
                return endMonotonicTime - startMonotonicTime;
        }

    }

    /**
     * If this context is closed.
     */
    public boolean isClosed() {
        return state == State.CLOSED;
    }

    public IPermissionManager getPermissionManager() {
        return celesta.getPermissionManager();
    }

    public ILoggingManager getLoggingManager() {
        return celesta.getLoggingManager();
    }

    public DBAdaptor getDbAdaptor() {
        return celesta.getDBAdaptor();
    }

    @Override
    public void close() {
        try {
            closeDataAccessors();
            if (conn != null) {
                conn.close();
            }
            if (celesta != null) {
                celesta.getProfiler().logCall(this);
            }
            endMonotonicTime = System.nanoTime();
            state = State.CLOSED;
        } catch (Exception e) {
            throw new CelestaException("Can't close callContext", e);
        }
    }

    /**
     * Duplicates callcontext with another JDBC connection.
     */
    public CallContext getCopy() {
        CallContext cc = new CallContext(userId);
        cc.activate(celesta, procName);
        return cc;
    }

}
