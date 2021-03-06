package ru.ifmo.ctddev.isaev.executable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import ru.ifmo.ctddev.isaev.*;
import ru.ifmo.ctddev.isaev.feature.measure.SymmetricUncertainty;
import ru.ifmo.ctddev.isaev.feature.measure.VDM;
import ru.ifmo.ctddev.isaev.melif.impl.*;
import ru.ifmo.ctddev.isaev.point.Point;
import ru.ifmo.ctddev.isaev.results.RunStats;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


/**
 * @author iisaev
 */
public class GiantComparison extends Comparison {
    private static final Logger LOGGER = LoggerFactory.getLogger(MultipleComparison.class);

    private static final Score score = new F1Score();

    private static final RelevanceMeasure[] MEASURES = new RelevanceMeasure[] {new VDM(), new FitCriterion(), new SymmetricUncertainty(), new SpearmanRankCorrelation()};


    protected static double getScore(DataSetPair dsPair) {
        Classifier classifier = Classifiers.SVM.newClassifier();
        TrainedClassifier trained = classifier.train(dsPair.getTrainSet());
        List<Integer> actual = trained.test(dsPair.getTestSet())
                .stream()
                .map(d -> (int) Math.round(d))
                .collect(Collectors.toList());
        List<Integer> expectedValues = dsPair.getTestSet().toInstanceSet().getInstances().stream().map(DataInstance::getClazz).collect(Collectors.toList());
        return score.calculate(expectedValues, actual);
    }

    public static void main(String[] args) throws FileNotFoundException {
        new File("table_results").mkdir();

        String startTimeString = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd_MM_yyyy_HH:mm"));
        MDC.put("fileName", startTimeString + "/COMMON");
        Point[] points = new Point[] {
                new Point(1.0, 0.0, 0.0, 0.0),
                new Point(0.0, 1.0, 0.0, 0.0),
                new Point(0.0, 0.0, 1.0, 0.0),
                new Point(0.0, 0.0, 0.0, 1.0),
                new Point(1.0, 1.0, 1.0, 1.0),
        };
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        DataSetReader dataSetReader = new DataSetReader();
        File dataSetDir = new File(args[0]);
        assert dataSetDir.exists();
        assert dataSetDir.isDirectory();
        final boolean[] firstTime = {true};
        List<List<RunStats>> results = Arrays.asList(dataSetDir.listFiles()).stream()
                .filter(f -> f.getAbsolutePath().endsWith(".csv"))
                .map(file -> {
                    MDC.put("fileName", startTimeString + "/" + file.getName());
                    return file;
                })
                .map(dataSetReader::readCsv)
                .map(dataSet -> {
                    List<Integer> order = IntStream.range(0, dataSet.getInstanceCount()).mapToObj(i -> i).collect(Collectors.toList());
                    Collections.shuffle(order);
                    DataSetSplitter dataSetSplitter = new OrderSplitter(10, order);
                    List<RunStats> allStats = new ArrayList<>();
                    double delta = 0.1;
                    Integer threads = 4;
                    LOGGER.info("Threads {}", threads);
                    AlgorithmConfig config = new AlgorithmConfig(delta,
                            new SequentalEvaluator(Classifiers.SVM,
                                    new PreferredSizeFilter(100),
                                    dataSetSplitter, new F1Score()), MEASURES);
                    allStats.add(new BasicMeLiF(config, dataSet).run(points));
                    System.gc();
                    allStats.add(new PriorityQueueMeLiF(config, dataSet, threads).run("Q50", 50));
                    System.gc();
                    allStats.add(new PriorityQueueMeLiF(config, dataSet, threads).run("Q75", 75));
                    System.gc();
                    allStats.add(new MultiArmedBanditMeLiF(config, dataSet, threads, 2).run("MA50", 50));
                    System.gc();
                    allStats.add(new MultiArmedBanditMeLiF(config, dataSet, threads, 2).run("MA75", 75));
                    System.gc();

                    PrintWriter writer = null;
                    try {
                        writer = new PrintWriter(new FileOutputStream("table_results/" + startTimeString + ".csv", true));
                        if (firstTime[0]) {
                            writer.append(csvHeader(allStats));
                            firstTime[0] = false;
                        }

                        printResults(writer, allStats);
                        writer.close();
                    } catch (FileNotFoundException e) {
                        throw new IllegalArgumentException(e);
                    }

                    MDC.remove("fileName");
                    return allStats;

                })
                .collect(Collectors.toList());
        MDC.put("fileName", "COMMON-" + startTimeString);

        PrintWriter writer = new PrintWriter("table_results/" + startTimeString + ".csv");
        writer.println(fullCsvRepresentation(results));
        writer.close();
    }

    private static void printResults(PrintWriter writer, List<RunStats> allStats) {
        writer.append(csvRepresentation(allStats));
    }

    private static Collection<RunStats> getMultiArmedStats(Integer threads, Double delta, FoldsEvaluator foldsEvaluator, FeatureDataSet dataSet) {
        AlgorithmConfig config = new AlgorithmConfig(delta, foldsEvaluator, MEASURES);
        List<RunStats> allStats = new ArrayList<>();
        for (Integer splitNumber : Arrays.asList(2, 3)) {
            MultiArmedBanditMeLiF meLiF1 = new MultiArmedBanditMeLiF(config, dataSet, threads, splitNumber);
            RunStats f10sqrtStats = meLiF1.run(
                    String.format("MultiArmedF%sD%sE%sT%sF10sqrt",
                            ((PreferredSizeFilter) foldsEvaluator.getDataSetFilter()).getPreferredSize(),
                            FeatureSelectionAlgorithm.FORMAT.format(delta),
                            foldsEvaluator.getName(),
                            foldsEvaluator.getDataSetSplitter().getTestPercent())
                    , meLiF1.getPointQueuesNumber() + (int) (10 * Math.sqrt(threads)));
            MultiArmedBanditMeLiF meLiF2 = new MultiArmedBanditMeLiF(config, dataSet, threads, splitNumber);
            RunStats f20sqrtStats = meLiF2.run(
                    String.format("MultiArmedF%sD%sE%sT%sF20sqrt",
                            ((PreferredSizeFilter) foldsEvaluator.getDataSetFilter()).getPreferredSize(),
                            FeatureSelectionAlgorithm.FORMAT.format(delta),
                            foldsEvaluator.getName(),
                            foldsEvaluator.getDataSetSplitter().getTestPercent())
                    , meLiF2.getPointQueuesNumber() + (int) (20 * Math.sqrt(threads)));
            MultiArmedBanditMeLiF meLiF3 = new MultiArmedBanditMeLiF(config, dataSet, threads, splitNumber);
            RunStats f10linStats = meLiF3.run(
                    String.format("MultiArmedF%sD%sE%sT%sF10lin",
                            ((PreferredSizeFilter) foldsEvaluator.getDataSetFilter()).getPreferredSize(),
                            FeatureSelectionAlgorithm.FORMAT.format(delta),
                            foldsEvaluator.getName(),
                            foldsEvaluator.getDataSetSplitter().getTestPercent())
                    , meLiF3.getPointQueuesNumber() + 10 * threads);
            allStats.add(f10sqrtStats);
            allStats.add(f20sqrtStats);
            allStats.add(f10linStats);
        }
        return allStats;
    }

    private static Collection<RunStats> getPriorityStats(Integer threads, Double delta, FoldsEvaluator foldsEvaluator, FeatureDataSet dataSet) {
        AlgorithmConfig config = new AlgorithmConfig(delta, foldsEvaluator, MEASURES);
        RunStats f10Stats = new PriorityQueueMeLiF(config, dataSet, threads).run(
                String.format("PrQueueF%sD%sE%sT%sF10",
                        ((PreferredSizeFilter) foldsEvaluator.getDataSetFilter()).getPreferredSize(),
                        FeatureSelectionAlgorithm.FORMAT.format(delta),
                        foldsEvaluator.getName(),
                        foldsEvaluator.getDataSetSplitter().getTestPercent())
                , 22 + 10 * threads);
        RunStats f5Stats = new PriorityQueueMeLiF(config, dataSet, threads).run(
                String.format("PrQueueF%sD%sE%sT%sF5",
                        ((PreferredSizeFilter) foldsEvaluator.getDataSetFilter()).getPreferredSize(),
                        FeatureSelectionAlgorithm.FORMAT.format(delta),
                        foldsEvaluator.getName(),
                        foldsEvaluator.getDataSetSplitter().getTestPercent())
                , 22 + 5 * threads);
        return Arrays.asList(f10Stats, f5Stats);
    }

    private static Collection<? extends RunStats> getStupidParallelStats(Double delta, FoldsEvaluator foldsEvaluator, Point[] points, FeatureDataSet dataSet) {
        AlgorithmConfig config = new AlgorithmConfig(delta, foldsEvaluator, MEASURES);
        RunStats stupidParallel = new StupidParallelMeLiF(config, dataSet).run(
                String.format("StupidF%sD%sE%sT%s",
                        ((PreferredSizeFilter) foldsEvaluator.getDataSetFilter()).getPreferredSize(),
                        FeatureSelectionAlgorithm.FORMAT.format(delta),
                        foldsEvaluator.getName(),
                        foldsEvaluator.getDataSetSplitter().getTestPercent())
                , points, 0);
        RunStats stupidParallel2 = new StupidParallelMeLiF2(config, dataSet).run(
                String.format("Stupid2F%sD%sE%sT%s",
                        ((PreferredSizeFilter) foldsEvaluator.getDataSetFilter()).getPreferredSize(),
                        FeatureSelectionAlgorithm.FORMAT.format(delta),
                        foldsEvaluator.getName(),
                        foldsEvaluator.getDataSetSplitter().getTestPercent())
                , points, 0);
        return Arrays.asList(stupidParallel, stupidParallel2);
    }

    private static RunStats getBasicStats(Double delta, FoldsEvaluator foldsEvaluator, Point[] points, FeatureDataSet dataSet) {

        AlgorithmConfig config = new AlgorithmConfig(delta, foldsEvaluator, MEASURES);
        return new BasicMeLiF(config, dataSet).run(
                String.format("BasicF%sD%sE%sT%s",
                        ((PreferredSizeFilter) foldsEvaluator.getDataSetFilter()).getPreferredSize(),
                        FeatureSelectionAlgorithm.FORMAT.format(delta),
                        foldsEvaluator.getName(),
                        foldsEvaluator.getDataSetSplitter().getTestPercent())
                , points, 0);
    }
}
