package datawave.core.iterators;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.DefaultConfiguration;
import org.apache.accumulo.core.iterators.IteratorEnvironment;
import org.apache.accumulo.core.util.threads.ThreadPools;
import org.apache.log4j.Logger;

/**
 *
 */
public class IteratorThreadPoolManager {
    private static final Logger log = Logger.getLogger(IteratorThreadPoolManager.class);
    private static final String IVARATOR_THREAD_PROP = "tserver.datawave.ivarator.threads";
    private static final String IVARATOR_THREAD_NAME = "DATAWAVE Ivarator";
    private static final String EVALUATOR_THREAD_PROP = "tserver.datawave.evaluation.threads";
    private static final String EVALUATOR_THREAD_NAME = "DATAWAVE Evaluation";
    private static final int DEFAULT_THREAD_POOL_SIZE = 100;

    private Map<String,ExecutorService> threadPools = new TreeMap<>();

    private static final Object instanceSemaphore = new Object();
    private static final String instanceId = Integer.toHexString(instanceSemaphore.hashCode());
    private static volatile IteratorThreadPoolManager instance;

    private IteratorThreadPoolManager(IteratorEnvironment env) {
        // create the thread pools
        createExecutorService(IVARATOR_THREAD_PROP, IVARATOR_THREAD_NAME, env);
        createExecutorService(EVALUATOR_THREAD_PROP, EVALUATOR_THREAD_NAME, env);
    }

    private ThreadPoolExecutor createExecutorService(final String prop, final String name, IteratorEnvironment env) {
        final AccumuloConfiguration accumuloConfiguration;
        if (env != null) {
            accumuloConfiguration = env.getConfig();
        } else {
            accumuloConfiguration = DefaultConfiguration.getInstance();
        }
        final ThreadPoolExecutor service = createExecutorService(getMaxThreads(prop, accumuloConfiguration), name + " (" + instanceId + ')');
        threadPools.put(name, service);
        ThreadPools.getServerThreadPools().createGeneralScheduledExecutorService(accumuloConfiguration).scheduleWithFixedDelay(() -> {
            try {
                int max = getMaxThreads(prop, accumuloConfiguration);
                if (service.getMaximumPoolSize() != max) {
                    log.info("Changing " + prop + " to " + max);
                    service.setMaximumPoolSize(max);
                    service.setCorePoolSize(max);
                }
            } catch (Throwable t) {
                log.error(t, t);
            }
        }, 1, 10, TimeUnit.SECONDS);
        return service;
    }

    private ThreadPoolExecutor createExecutorService(int maxThreads, String name) {
        ThreadPoolExecutor pool = ThreadPools.getServerThreadPools().createThreadPool(maxThreads, maxThreads, 5 * 60, TimeUnit.SECONDS, name,
                        new LinkedBlockingQueue<>(), false);
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private int getMaxThreads(final String prop, AccumuloConfiguration conf) {
        if (conf != null) {
            Map<String,String> properties = new TreeMap<>();
            conf.getProperties(properties, k -> Objects.equals(k, prop));
            if (properties.containsKey(prop)) {
                return Integer.parseInt(properties.get(prop));
            }
        }
        return DEFAULT_THREAD_POOL_SIZE;
    }

    private static IteratorThreadPoolManager instance(IteratorEnvironment env) {
        if (instance == null) {
            synchronized (instanceSemaphore) {
                if (instance == null) {
                    instance = new IteratorThreadPoolManager(env);
                }
            }
        }
        return instance;
    }

    private Future<?> execute(String name, final Runnable task, final String taskName) {
        return threadPools.get(name).submit(() -> {
            String oldName = Thread.currentThread().getName();
            Thread.currentThread().setName(oldName + " -> " + taskName);
            try {
                task.run();
            } finally {
                Thread.currentThread().setName(oldName);
            }
        });
    }

    public static Future<?> executeIvarator(Runnable task, String taskName, IteratorEnvironment env) {
        return instance(env).execute(IVARATOR_THREAD_NAME, task, taskName);
    }

    public static Future<?> executeEvaluation(Runnable task, String taskName, IteratorEnvironment env) {
        return instance(env).execute(EVALUATOR_THREAD_NAME, task, taskName);
    }

}
