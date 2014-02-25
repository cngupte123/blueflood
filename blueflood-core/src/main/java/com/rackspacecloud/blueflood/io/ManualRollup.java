package com.rackspacecloud.blueflood.io;

import com.codahale.metrics.*;
import com.codahale.metrics.Timer;
import com.google.common.base.Function;
import com.netflix.astyanax.model.Column;
import com.netflix.astyanax.model.ColumnFamily;
import com.netflix.astyanax.model.ColumnList;
import com.netflix.astyanax.model.Row;
import com.netflix.astyanax.recipes.reader.AllRowsReader;
import com.rackspacecloud.blueflood.concurrent.ThreadPoolBuilder;
import com.rackspacecloud.blueflood.io.handlers.ReRollWork;
import com.rackspacecloud.blueflood.rollup.Granularity;
import com.rackspacecloud.blueflood.service.*;
import com.rackspacecloud.blueflood.types.*;
import com.rackspacecloud.blueflood.utils.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.*;

public class ManualRollup extends AstyanaxIO {

    private static final Meter nullRows = Metrics.meter(ManualRollup.class, "Null Rows Found");
    private static final Timer rerollTimerPerShard = Metrics.timer(ManualRollup.class, "Time taken to rollup per shard");
    private static final Set<ColumnFamily> columnFamiliesEnabled = new HashSet<ColumnFamily>();

    private final ThreadPoolExecutor rollupExecutors = new ThreadPoolBuilder()
                    .withUnboundedQueue()
                    .withCorePoolSize(Configuration.getInstance().getIntegerProperty(CoreConfig.MAX_REROLL_THREADS))
                    .withMaxPoolSize(Configuration.getInstance().getIntegerProperty(CoreConfig.MAX_REROLL_THREADS))
                    .withName("ReRollup ThreadPool")
                    .build();

    private static final Logger log = LoggerFactory.getLogger(ManualRollup.class);
    private static final long START_MILLIS = Configuration.getInstance().getLongProperty(CoreConfig.START_MILLIS);
    private static final long STOP_MILLIS = Configuration.getInstance().getLongProperty(CoreConfig.STOP_MILLIS);

    static {

        if (Configuration.getInstance().getBooleanProperty(CoreConfig.METRICS_5M_ENABLED)) { columnFamiliesEnabled.add(CassandraModel.CF_METRICS_5M); }
        if (Configuration.getInstance().getBooleanProperty(CoreConfig.METRICS_20M_ENABLED)) { columnFamiliesEnabled.add(CassandraModel.CF_METRICS_20M); }
        if (Configuration.getInstance().getBooleanProperty(CoreConfig.METRICS_60M_ENABLED)) { columnFamiliesEnabled.add(CassandraModel.CF_METRICS_60M); }
        if (Configuration.getInstance().getBooleanProperty(CoreConfig.METRICS_240M_ENABLED)) { columnFamiliesEnabled.add(CassandraModel.CF_METRICS_240M); }
        if (Configuration.getInstance().getBooleanProperty(CoreConfig.METRICS_1440M_ENABLED)) { columnFamiliesEnabled.add(CassandraModel.CF_METRICS_1440M); }

    }

    public void startManualRollup() {
        System.out.println("Logging all (" + columnFamiliesEnabled.size() + ") columnfamilies that we will manually rollup FROM: " + START_MILLIS + "\tTO:" + STOP_MILLIS);
        System.out.println("Logging all (" + columnFamiliesEnabled.size() + ") columnfamilies that we will manually rollup FROM: " + START_MILLIS + "\tTO:" + STOP_MILLIS);
        for (ColumnFamily columnFamily : columnFamiliesEnabled) {
            System.out.println("\t~\tWILL manually rollup " + columnFamily.getName());
        }
        for (ColumnFamily<Locator, Long> columnFamily : columnFamiliesEnabled) {
            System.out.println("\t~\t~\tSTARTING to manually rollup " + columnFamily.getName());
            rollupCf(columnFamily);
            System.out.println("\t~\t~\tFinished rolling up " + columnFamily.getName());
        }
        System.out.println("\t~\tCompleted");
    }

    private void rollupCf(final ColumnFamily<Locator, Long> columnFamily) {

        final Granularity gran = Granularity.fromString(columnFamily.getName());
        Function<Row<Long, Locator>, Boolean> rowFunction = new Function<Row<Long, Locator>, Boolean>() {

            @Override
            public Boolean apply(@Nullable Row<Long, Locator> row) {

                Timer.Context rerollContext = rerollTimerPerShard.time();

                if (row == null) {
                    log.warn("Found a null row");
                    nullRows.mark();
                    return true;
                }

                System.out.println("\t~\t~\tReRolling up for shard " + row.getKey());
                ColumnList<Locator> columns = row.getColumns();
                ArrayList<ReRollWork> work = new ArrayList<ReRollWork>();

                for (Column<Locator> column : columns) {
                    Locator locator = column.getName();
                    work.add(new ReRollWork(locator, gran, new Range(START_MILLIS, STOP_MILLIS)));
                }

                List<Future<Boolean>> retList = null;
                try {
                    retList = rollupExecutors.invokeAll(work);
                } catch (InterruptedException e) {}

                boolean notDone = true;

                //We need to do this dance, because 1)We need to stop whenever an exception has encountered 2)Just keep on adding tasks to the work queue and blowing the stack. this will essentially block per shard
                while (notDone) {
                    int doneCount = 0;

                    for(Future<Boolean> myFut : retList) {
                        if(myFut.isDone()) {
                            doneCount++;
                        }
                    }

                    if(doneCount == retList.size())
                        notDone = false;
                }

                try {

                    for (Future<Boolean> retFut : retList) {
                        //Calling get will throw any suppressed exception from ReRoll Work
                        retFut.get();
                    }

                } catch (Exception e) {
                    System.err.println("Fatal exception while re-rolling data"+e);
                    throw new RuntimeException();
                } finally {
                    rerollContext.stop();
                }

                return true;
            }
        };


        try {
            //Get all the locators per shard one by one
            boolean result = new AllRowsReader.Builder<Long, Locator>(getKeyspace(), CassandraModel.CF_METRICS_LOCATOR)
                                       .withPageSize(1) //Wide rows
                                       .withConcurrencyLevel(1)
                                       .forEachRow(rowFunction)
                                       .build()
                                       .call();
        } catch (Exception e) {
            System.err.println("Fatal error in AllRowsReader"+e);
            throw new RuntimeException(e);
        } finally {
            rollupExecutors.shutdown();
        }
    }
}
