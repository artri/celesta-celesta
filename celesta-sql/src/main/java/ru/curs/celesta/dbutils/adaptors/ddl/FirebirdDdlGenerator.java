package ru.curs.celesta.dbutils.adaptors.ddl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.curs.celesta.CelestaException;
import ru.curs.celesta.DBType;
import ru.curs.celesta.dbutils.adaptors.DBAdaptor;
import ru.curs.celesta.dbutils.meta.DbColumnInfo;
import ru.curs.celesta.dbutils.meta.DbIndexInfo;
import ru.curs.celesta.event.TriggerQuery;
import ru.curs.celesta.score.*;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class FirebirdDdlGenerator extends DdlGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(FirebirdDdlGenerator.class);

    public FirebirdDdlGenerator(DBAdaptor dmlAdaptor) {
        super(dmlAdaptor);
    }

    @Override
    List<String> dropParameterizedView(String schemaName, String viewName, Connection conn) {
        return null;
    }

    @Override
    List<String> dropIndex(Grain g, DbIndexInfo dBIndexInfo) {
        String sql = String.format(
            "DROP INDEX %s",
            tableString(g.getName(), dBIndexInfo.getIndexName())
        );

        return Arrays.asList(sql);
    }

    @Override
    String dropTriggerSql(TriggerQuery query) {
        return null;
    }

    @Override
    public String dropPk(TableElement t, String pkName) {
        return null;
    }

    @Override
    DBType getType() {
        return DBType.FIREBIRD;
    }

    @Override
    List<String> updateVersioningTrigger(Connection conn, TableElement t) {
        List<String> result = new ArrayList<>();
        // First of all, we are about to check if trigger exists
        try {
            TriggerQuery query = new TriggerQuery().withSchema(t.getGrain().getName())
                .withName("versioncheck")
                .withTableName(t.getName());
            boolean triggerExists = this.triggerExists(conn, query);

            if (t instanceof VersionedElement) {
                VersionedElement ve = (VersionedElement) t;

                String sql;
                if (ve.isVersioned()) {
                    if (!triggerExists) {
                        // CREATE TRIGGER
                        sql =
                            "CREATE TRIGGER \"versioncheck\" for " + tableString(t.getGrain().getName(), t.getName())
                                + " BEFORE UPDATE \n"
                                + " AS \n"
                                + " BEGIN \n"
                                + "   IF (OLD.\"recversion\" = NEW.\"recversion\")\n"
                                + "     THEN NEW.\"recversion\" = NEW.\"recversion\" + 1;"
                                + "   ELSE "
                                + "     EXCEPTION VERSION_CHECK_ERROR;"
                                + " END";
                        result.add(sql);
                        this.rememberTrigger(query);
                    }
                } else {
                    if (triggerExists) {
                        // DROP TRIGGER
                        result.add(dropTrigger(query));
                    }
                }
            }
        } catch (CelestaException e) {
            throw new CelestaException("Could not update version check trigger on %s.%s: %s", t.getGrain().getName(),
                t.getName(), e.getMessage());
        }

        return result;
    }

    @Override
    List<String> createIndex(Index index) {
        return null;
    }

    @Override
    List<String> updateColumn(Connection conn, Column c, DbColumnInfo actual) {
        return null;
    }

    @Override
    SQLGenerator getViewSQLGenerator() {
        return null;
    }

    @Override
    List<String> createParameterizedView(ParameterizedView pv) {
        return null;
    }

    @Override
    Optional<String> dropAutoIncrement(Connection conn, TableElement t) {
        return Optional.empty();
    }

    @Override
    public List<String> dropTableTriggersForMaterializedViews(Connection conn, BasicTable t) {
        return null;
    }

    @Override
    public List<String> createTableTriggersForMaterializedViews(BasicTable t) {
        return null;
    }

    @Override
    String truncDate(String dateStr) {
        return null;
    }
}
