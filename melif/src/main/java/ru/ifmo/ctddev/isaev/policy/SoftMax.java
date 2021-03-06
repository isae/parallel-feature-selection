package ru.ifmo.ctddev.isaev.policy;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;


/**
 * @author iisaev
 */
public class SoftMax extends BanditStrategy {
    private final double tau;

    public SoftMax(double tau, int arms) {
        super(arms);
        this.tau = tau;
        if (tau < 0 | tau > 1) {
            throw new IllegalArgumentException(String.format("Invalid temperature param: %f", tau));
        }
    }

    @Override
    public void processPoint(Collection lastTries, Function<Integer, Optional<Double>> action) {
        int arm;
        double expSum = IntStream.range(0, getArms())
                .mapToDouble(i -> Math.exp(mu(i) / tau)).sum();
        arm = IntStream.range(0, getArms())
                .mapToObj(i -> i)
                .sorted(Comparator.comparingDouble(i -> Math.exp(mu(i) / (tau * expSum))))
                .findFirst()
                .get();
        double result = action.apply(arm).orElseThrow(() ->
                new IllegalStateException("Optional.EMPTY is not supported in softmax")
        );
        ++getVisitedNumber()[arm];
        getVisitedSum()[arm] += result;
    }
}
