package ru.ifmo.ctddev.isaev.dataset;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


/**
 * @author iisaev
 */
public class InstanceDataSet extends DataSet {
    private final List<DataInstance> instances;

    protected InstanceDataSet(List<DataInstance> instances) {
        super("");
        if (instances.isEmpty()) {
            throw new IllegalArgumentException("Number of instances must not be equal to zero");
        }
        this.instances = Collections.unmodifiableList(instances);
    }

    @Override
    public FeatureDataSet toFeatureSet() {
        List<Integer> classes = instances.stream().map(DataInstance::getClazz).collect(Collectors.toList());
        List<Feature> features = IntStream.range(0, instances.get(0).getValues().size()).mapToObj(i -> {
            List<Integer> values = instances.stream().map(inst -> inst.getValues().get(i)).collect(Collectors.toList());
            return new Feature(values);
        }).collect(Collectors.toList());
        return new FeatureDataSet(features, classes, getName());
    }

    @Override
    public InstanceDataSet toInstanceSet() {
        return this;
    }

    @Override
    public int getFeatureCount() {
        return instances.get(0).getValues().size();
    }

    @Override
    public int getInstanceCount() {
        return instances.size();
    }

    public List<DataInstance> getInstances() {
        return instances;
    }
}
