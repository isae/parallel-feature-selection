package ru.ifmo.ctddev.isaev.melif.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ifmo.ctddev.isaev.AlgorithmConfig;
import ru.ifmo.ctddev.isaev.dataset.DataSet;
import ru.ifmo.ctddev.isaev.executor.PriorityThreadPoolExecutor;
import ru.ifmo.ctddev.isaev.result.Point;
import ru.ifmo.ctddev.isaev.result.PriorityPoint;
import ru.ifmo.ctddev.isaev.result.RunStats;
import ru.ifmo.ctddev.isaev.result.SelectionResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.IntStream;


/**
 * Implementation, that evaluates each point in separate thread
 *
 * @author iisaev
 */
public class PriorityQueueMeLiF extends FeatureSelectionAlgorithm {
    private final PriorityThreadPoolExecutor executorService;

    private static final Logger LOGGER = LoggerFactory.getLogger(PriorityQueueMeLiF.class);

    public ExecutorService getExecutorService() {
        return executorService;
    }

    protected final Set<Point> visitedPoints = new ConcurrentSkipListSet<>();

    private final List<Point> startingPoints = new ArrayList<>();

    public PriorityQueueMeLiF(AlgorithmConfig config, DataSet dataSet, int threads) {
        super(config, dataSet);
        int dimension = config.getMeasures().length;

        double[] allEqual = new double[dimension];
        Arrays.fill(allEqual, 1.0);
        startingPoints.add(new PriorityPoint(1.0, allEqual));

        IntStream.range(0, dimension).forEach(dim -> {
            double[] coordinates = new double[dimension];
            coordinates[dim] = 1.0;
            startingPoints.add(new PriorityPoint(1.0, coordinates));
        });
        this.executorService = new PriorityThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(50));
    }

    private List<Point> getNeighbours(Point point) {
        List<Point> points = new ArrayList<>();
        IntStream.range(0, config.getMeasures().length).forEach(i -> {
            Point plusDelta = new Point(point, coords -> coords[i] += config.getDelta());
            Point minusDelta = new Point(point, coords -> coords[i] -= config.getDelta());
            points.add(plusDelta);
            points.add(minusDelta);
        });
        return points;
    }

    class PointProcessingTask implements Callable<Double> {
        PriorityPoint point;

        Supplier<Boolean> stopCondition;

        RunStats runStats;

        public PointProcessingTask(PriorityPoint point, Supplier<Boolean> stopCondition, RunStats runStats) {
            this.point = point;
            this.stopCondition = stopCondition;
            this.runStats = runStats;
        }

        @Override
        public Double call() throws Exception {
            if (stopCondition.get()) {
                return 0.0;
            }
            if (visitedPoints.contains(point)) {
                logger.warn("Point is already processed");
                return 0.0;
            }
            logger.info("Processing point {}", point);
            SelectionResult res = getSelectionResult(point, runStats);
            visitedPoints.add(point);
            runStats.updateBestResult(res);
            List<Point> neighbours = getNeighbours(point);
            neighbours.forEach(p -> {
                if (!visitedPoints.contains(p)) {
                    executorService.submit(new PointProcessingTask(new PriorityPoint(res.getF1Score(), p.getCoordinates()), stopCondition, runStats), res.getF1Score());
                }
            });
            return res.getF1Score();
        }
    }

    public RunStats run() {
        return run(true);
    }

    public RunStats run(boolean shutdown) {
        RunStats runStats = new RunStats(config, dataSet, "PriorityQueue");

        CountDownLatch latch = new CountDownLatch(100);
        startingPoints.forEach(point -> executorService.submit(new PointProcessingTask(new PriorityPoint(1.0, point.getCoordinates()), () -> {
            latch.countDown();
            return latch.getCount() == 0;
        }, runStats), 1.0));
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        if (shutdown) {
            executorService.shutdownNow();
        }
        runStats.setFinishTime(LocalDateTime.now());
        return runStats;
    }
}
