/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.runners;

import static com.google.cloud.dataflow.sdk.util.CoderUtils.encodeToByteArray;
import static com.google.cloud.dataflow.sdk.util.SerializableUtils.serializeToByteArray;
import static com.google.cloud.dataflow.sdk.util.StringUtils.byteArrayToJsonString;
import static com.google.cloud.dataflow.sdk.util.StringUtils.jsonStringToByteArray;
import static com.google.cloud.dataflow.sdk.util.Structs.addBoolean;
import static com.google.cloud.dataflow.sdk.util.Structs.addDictionary;
import static com.google.cloud.dataflow.sdk.util.Structs.addList;
import static com.google.cloud.dataflow.sdk.util.Structs.addLong;
import static com.google.cloud.dataflow.sdk.util.Structs.addObject;
import static com.google.cloud.dataflow.sdk.util.Structs.addString;
import static com.google.cloud.dataflow.sdk.util.Structs.getString;
import static com.google.common.base.Preconditions.checkArgument;

import com.google.api.client.util.Preconditions;
import com.google.api.services.dataflow.model.AutoscalingSettings;
import com.google.api.services.dataflow.model.DataflowPackage;
import com.google.api.services.dataflow.model.Disk;
import com.google.api.services.dataflow.model.Environment;
import com.google.api.services.dataflow.model.Job;
import com.google.api.services.dataflow.model.Step;
import com.google.api.services.dataflow.model.WorkerPool;
import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.Pipeline.PipelineVisitor;
import com.google.cloud.dataflow.sdk.coders.CannotProvideCoderException;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.CoderException;
import com.google.cloud.dataflow.sdk.coders.IterableCoder;
import com.google.cloud.dataflow.sdk.io.AvroIO;
import com.google.cloud.dataflow.sdk.io.BigQueryIO;
import com.google.cloud.dataflow.sdk.io.PubsubIO;
import com.google.cloud.dataflow.sdk.io.Read;
import com.google.cloud.dataflow.sdk.io.TextIO;
import com.google.cloud.dataflow.sdk.options.DataflowPipelineOptions;
import com.google.cloud.dataflow.sdk.options.DataflowPipelineWorkerPoolOptions.AutoscalingAlgorithmType;
import com.google.cloud.dataflow.sdk.options.StreamingOptions;
import com.google.cloud.dataflow.sdk.runners.dataflow.AvroIOTranslator;
import com.google.cloud.dataflow.sdk.runners.dataflow.BigQueryIOTranslator;
import com.google.cloud.dataflow.sdk.runners.dataflow.PubsubIOTranslator;
import com.google.cloud.dataflow.sdk.runners.dataflow.ReadTranslator;
import com.google.cloud.dataflow.sdk.runners.dataflow.TextIOTranslator;
import com.google.cloud.dataflow.sdk.transforms.AppliedPTransform;
import com.google.cloud.dataflow.sdk.transforms.Combine;
import com.google.cloud.dataflow.sdk.transforms.Create;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.Flatten;
import com.google.cloud.dataflow.sdk.transforms.GroupByKey;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.View;
import com.google.cloud.dataflow.sdk.transforms.windowing.DefaultTrigger;
import com.google.cloud.dataflow.sdk.transforms.windowing.Window;
import com.google.cloud.dataflow.sdk.util.CloudObject;
import com.google.cloud.dataflow.sdk.util.DoFnInfo;
import com.google.cloud.dataflow.sdk.util.OutputReference;
import com.google.cloud.dataflow.sdk.util.PropertyNames;
import com.google.cloud.dataflow.sdk.util.SerializableUtils;
import com.google.cloud.dataflow.sdk.util.WindowedValue;
import com.google.cloud.dataflow.sdk.util.WindowingStrategy;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PCollectionTuple;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.PInput;
import com.google.cloud.dataflow.sdk.values.POutput;
import com.google.cloud.dataflow.sdk.values.PValue;
import com.google.cloud.dataflow.sdk.values.TupleTag;
import com.google.cloud.dataflow.sdk.values.TypedPValue;
import com.google.common.base.Strings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * {@link DataflowPipelineTranslator} knows how to translate {@link Pipeline} objects
 * into Cloud Dataflow Service API {@link Job}s.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class DataflowPipelineTranslator {
  // Must be kept in sync with their internal counterparts.
  private static final Logger LOG = LoggerFactory.getLogger(DataflowPipelineTranslator.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * A map from {@link PTransform} subclass to the corresponding
   * {@link TransformTranslator} to use to translate that transform.
   *
   * <p> A static map that contains system-wide defaults.
   */
  private static Map<Class, TransformTranslator> transformTranslators =
      new HashMap<>();

  /** Provided configuration options. */
  private final DataflowPipelineOptions options;

  /**
   * Constructs a translator from the provided options.
   *
   * @param options Properties that configure the translator.
   *
   * @return The newly created translator.
   */
  public static DataflowPipelineTranslator fromOptions(
      DataflowPipelineOptions options) {
    return new DataflowPipelineTranslator(options);
  }

  private DataflowPipelineTranslator(DataflowPipelineOptions options) {
    this.options = options;
  }

  /**
   * Translates a {@link Pipeline} into a {@code JobSpecification}.
   */
  public JobSpecification translate(Pipeline pipeline, List<DataflowPackage> packages) {
    Translator translator = new Translator(pipeline);
    Job result = translator.translate(packages);
    return new JobSpecification(result, Collections.unmodifiableMap(translator.stepNames));
  }

  /**
   * The result of a job translation.
   *
   * <p>Used to pass the result {@link Job} and any state that was used to construct the job that
   * may be of use to other classes (eg the {@link PTransform} to StepName mapping).
   */
  public static class JobSpecification {
    private final Job job;
    private final Map<AppliedPTransform<?, ?, ?>, String> stepNames;

    public JobSpecification(Job job, Map<AppliedPTransform<?, ?, ?>, String> stepNames) {
      this.job = job;
      this.stepNames = stepNames;
    }

    public Job getJob() {
      return job;
    }

    /**
     * Returns the mapping of {@link AppliedPTransform AppliedPTransforms} to the internal step
     * name for that {@code AppliedPTransform}.
     */
    public Map<AppliedPTransform<?, ?, ?>, String> getStepNames() {
      return stepNames;
    }
  }

  public static String jobToString(Job job) {
    try {
      return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(job);
    } catch (JsonProcessingException exc) {
      throw new IllegalStateException("Failed to render Job as String.", exc);
    }
  }

  /////////////////////////////////////////////////////////////////////////////

  /**
   * Records that instances of the specified PTransform class
   * should be translated by default by the corresponding
   * {@link TransformTranslator}.
   */
  public static <TransformT extends PTransform> void registerTransformTranslator(
      Class<TransformT> transformClass,
      TransformTranslator<? extends TransformT> transformTranslator) {
    if (transformTranslators.put(transformClass, transformTranslator) != null) {
      throw new IllegalArgumentException(
          "defining multiple translators for " + transformClass);
    }
  }

  /**
   * Returns the {@link TransformTranslator} to use for instances of the
   * specified PTransform class, or null if none registered.
   */
  public <TransformT extends PTransform>
      TransformTranslator<TransformT> getTransformTranslator(Class<TransformT> transformClass) {
    return transformTranslators.get(transformClass);
  }

  /**
   * A {@link TransformTranslator} knows how to translate
   * a particular subclass of {@link PTransform} for the
   * Cloud Dataflow service. It does so by
   * mutating the {@link TranslationContext}.
   */
  public interface TransformTranslator<TransformT extends PTransform> {
    public void translate(TransformT transform,
                          TranslationContext context);
  }

  /**
   * The interface provided to registered callbacks for interacting
   * with the {@link DataflowPipelineRunner}, including reading and writing the
   * values of {@link PCollection}s and side inputs ({@link PCollectionView}s).
   */
  public interface TranslationContext {
    /**
     * Returns the configured pipeline options.
     */
    DataflowPipelineOptions getPipelineOptions();

    /**
     * Returns the input of the currently being translated transform.
     */
    <InputT extends PInput> InputT getInput(PTransform<InputT, ?> transform);

    /**
     * Returns the output of the currently being translated transform.
     */
    <OutputT extends POutput> OutputT getOutput(PTransform<?, OutputT> transform);

    /**
     * Returns the full name of the currently being translated transform.
     */
    String getFullName(PTransform<?, ?> transform);

    /**
     * Adds a step to the Dataflow workflow for the given transform, with
     * the given Dataflow step type.
     * This step becomes "current" for the purpose of {@link #addInput} and
     * {@link #addOutput}.
     */
    public void addStep(PTransform<?, ?> transform, String type);

    /**
     * Adds a pre-defined step to the Dataflow workflow. The given PTransform should be
     * consistent with the Step, in terms of input, output and coder types.
     *
     * <p> This is a low-level operation, when using this method it is up to
     * the caller to ensure that names do not collide.
     */
    public void addStep(PTransform<?, ? extends PValue> transform, Step step);

    /**
     * Sets the encoding for the current Dataflow step.
     */
    public void addEncodingInput(Coder<?> value);

    /**
     * Adds an input with the given name and value to the current
     * Dataflow step.
     */
    public void addInput(String name, Boolean value);

    /**
     * Adds an input with the given name and value to the current
     * Dataflow step.
     */
    public void addInput(String name, String value);

    /**
     * Adds an input with the given name and value to the current
     * Dataflow step.
     */
    public void addInput(String name, Long value);

    /**
     * Adds an input with the given name to the previously added Dataflow
     * step, coming from the specified input PValue.
     */
    public void addInput(String name, PInput value);

    /**
     * Adds an input that is a dictionary of strings to objects.
     */
    public void addInput(String name, Map<String, Object> elements);

    /**
     * Adds an input that is a list of objects.
     */
    public void addInput(String name, List<? extends Map<String, Object>> elements);

    /**
     * Adds an output with the given name to the previously added
     * Dataflow step, producing the specified output {@code PValue},
     * including its {@code Coder} if a {@code TypedPValue}.  If the
     * {@code PValue} is a {@code PCollection}, wraps its coder inside
     * a {@code WindowedValueCoder}.
     */
    public void addOutput(String name, PValue value);

    /**
     * Adds an output with the given name to the previously added
     * Dataflow step, producing the specified output {@code PValue},
     * including its {@code Coder} if a {@code TypedPValue}.  If the
     * {@code PValue} is a {@code PCollection}, wraps its coder inside
     * a {@code ValueOnlyCoder}.
     */
    public void addValueOnlyOutput(String name, PValue value);

    /**
     * Adds an output with the given name to the previously added
     * CollectionToSingleton Dataflow step, consuming the specified
     * input {@code PValue} and producing the specified output
     * {@code PValue}.  This step requires special treatment for its
     * output encoding.
     */
    public void addCollectionToSingletonOutput(String name,
                                               PValue inputValue,
                                               PValue outputValue);

    /**
     * Encode a PValue reference as an output reference.
     */
    public OutputReference asOutputReference(PValue value);
  }


  /////////////////////////////////////////////////////////////////////////////

  /**
   * Translates a Pipeline into the Dataflow representation.
   */
  class Translator implements PipelineVisitor, TranslationContext {
    /** The Pipeline to translate. */
    private final Pipeline pipeline;

    /** The Cloud Dataflow Job representation. */
    private final Job job = new Job();

    /**
     * Translator is stateful, as addProperty calls refer to the current step.
     */
    private Step currentStep;

    /**
     * A Map from AppliedPTransform to their unique Dataflow step names.
     */
    private final Map<AppliedPTransform<?, ?, ?>, String> stepNames = new HashMap<>();

    /**
     * A Map from PValues to their output names used by their producer
     * Dataflow steps.
     */
    private final Map<POutput, String> outputNames = new HashMap<>();

    /**
     * A Map from PValues to the Coders used for them.
     */
    private final Map<POutput, Coder<?>> outputCoders = new HashMap<>();

    /**
     * The transform currently being applied.
     */
    private AppliedPTransform<?, ?, ?> currentTransform;

    /**
     * Constructs a Translator that will translate the specified
     * Pipeline into Dataflow objects.
     */
    public Translator(Pipeline pipeline) {
      this.pipeline = pipeline;
    }

    /**
     * Translates this Translator's pipeline onto its writer.
     * @return a Job definition filled in with the type of job, the environment,
     * and the job steps.
     */
    public Job translate(List<DataflowPackage> packages) {
      job.setName(options.getJobName().toLowerCase());

      Environment environment = new Environment();
      job.setEnvironment(environment);

      try {
        environment.setSdkPipelineOptions(
            MAPPER.readValue(MAPPER.writeValueAsBytes(options), Map.class));
      } catch (IOException e) {
        throw new IllegalArgumentException(
            "PipelineOptions specified failed to serialize to JSON.", e);
      }

      WorkerPool workerPool = new WorkerPool();

      workerPool.setKind(options.getWorkerPoolType());
      if (options.getTeardownPolicy() != null) {
        workerPool.setTeardownPolicy(options.getTeardownPolicy().getTeardownPolicyName());
      }

      if (options.isStreaming()) {
        job.setType("JOB_TYPE_STREAMING");
      } else {
        job.setType("JOB_TYPE_BATCH");
        workerPool.setDiskType(options.getWorkerDiskType());
      }

      if (options.getWorkerMachineType() != null) {
        workerPool.setMachineType(options.getWorkerMachineType());
      }

      workerPool.setPackages(packages);
      workerPool.setNumWorkers(options.getNumWorkers());
      if (options.getDiskSourceImage() != null) {
        workerPool.setDiskSourceImage(options.getDiskSourceImage());
      }

      if (options.isStreaming()) {
        // Use separate data disk for streaming.
        Disk disk = new Disk();
        disk.setDiskType(options.getWorkerDiskType());
        workerPool.setDataDisks(Collections.singletonList(disk));
      }
      if (!Strings.isNullOrEmpty(options.getZone())) {
        workerPool.setZone(options.getZone());
      }
      if (!Strings.isNullOrEmpty(options.getNetwork())) {
        workerPool.setNetwork(options.getNetwork());
      }
      if (options.getDiskSizeGb() > 0) {
        workerPool.setDiskSizeGb(options.getDiskSizeGb());
      }
      if (!options.getAutoscalingAlgorithm().equals(AutoscalingAlgorithmType.NONE)) {
        AutoscalingSettings settings = new AutoscalingSettings();
        settings.setAlgorithm(options.getAutoscalingAlgorithm().getAlgorithm());
        settings.setMaxNumWorkers(options.getMaxNumWorkers());
        workerPool.setAutoscalingSettings(settings);
      }

      List<WorkerPool> workerPools = new LinkedList<>();

      workerPools.add(workerPool);
      environment.setWorkerPools(workerPools);

      pipeline.traverseTopologically(this);
      return job;
    }

    @Override
    public DataflowPipelineOptions getPipelineOptions() {
      return options;
    }

    @Override
    public <InputT extends PInput> InputT getInput(PTransform<InputT, ?> transform) {
      return (InputT) getCurrentTransform(transform).getInput();
    }

    @Override
    public <OutputT extends POutput> OutputT getOutput(PTransform<?, OutputT> transform) {
      return (OutputT) getCurrentTransform(transform).getOutput();
    }

    @Override
    public String getFullName(PTransform<?, ?> transform) {
      return getCurrentTransform(transform).getFullName();
    }

    private AppliedPTransform<?, ?, ?> getCurrentTransform(PTransform<?, ?> transform) {
      checkArgument(
          currentTransform != null && currentTransform.getTransform() == transform,
          "can only be called with current transform");
      return currentTransform;
    }

    @Override
    public void enterCompositeTransform(TransformTreeNode node) {
    }

    @Override
    public void leaveCompositeTransform(TransformTreeNode node) {
    }

    @Override
    public void visitTransform(TransformTreeNode node) {
      PTransform<?, ?> transform = node.getTransform();
      TransformTranslator translator =
          getTransformTranslator(transform.getClass());
      if (translator == null) {
        throw new IllegalStateException(
            "no translator registered for " + transform);
      }
      LOG.debug("Translating {}", transform);
      currentTransform = AppliedPTransform.of(
          node.getFullName(), node.getInput(), node.getOutput(), (PTransform) transform);
      translator.translate(transform, this);
      currentTransform = null;
    }

    @Override
    public void visitValue(PValue value, TransformTreeNode producer) {
      LOG.debug("Checking translation of {}", value);
      if (value.getProducingTransformInternal() == null) {
        throw new RuntimeException(
            "internal error: expecting a PValue "
            + "to have a producingTransform");
      }
      if (!producer.isCompositeNode()) {
        // Primitive transforms are the only ones assigned step names.
        asOutputReference(value);
      }
    }

    @Override
    public void addStep(PTransform<?, ?> transform, String type) {
      String stepName = genStepName();
      if (stepNames.put(getCurrentTransform(transform), stepName) != null) {
        throw new IllegalArgumentException(
            transform + " already has a name specified");
      }
      // Start the next "steps" list item.
      List<Step> steps = job.getSteps();
      if (steps == null) {
        steps = new LinkedList<>();
        job.setSteps(steps);
      }

      currentStep = new Step();
      currentStep.setName(stepName);
      currentStep.setKind(type);
      steps.add(currentStep);
      addInput(PropertyNames.USER_NAME, getFullName(transform));
    }

    @Override
    public void addStep(PTransform<?, ? extends PValue> transform, Step original) {
      Step step = original.clone();
      String stepName = step.getName();
      if (stepNames.put(getCurrentTransform(transform), stepName) != null) {
        throw new IllegalArgumentException(transform + " already has a name specified");
      }

      Map<String, Object> properties = step.getProperties();
      if (properties != null) {
        @Nullable List<Map<String, Object>> outputInfoList = null;
        try {
          // TODO: This should be done via a Structs accessor.
          @Nullable List<Map<String, Object>> list =
              (List<Map<String, Object>>) properties.get(PropertyNames.OUTPUT_INFO);
          outputInfoList = list;
        } catch (Exception e) {
          throw new RuntimeException("Inconsistent dataflow pipeline translation", e);
        }
        if (outputInfoList != null && outputInfoList.size() > 0) {
          Map<String, Object> firstOutputPort = outputInfoList.get(0);
          @Nullable String name;
          try {
            name = getString(firstOutputPort, PropertyNames.OUTPUT_NAME);
          } catch (Exception e) {
            name = null;
          }
          if (name != null) {
            registerOutputName(getOutput(transform), name);
          }
        }
      }

      List<Step> steps = job.getSteps();
      if (steps == null) {
        steps = new LinkedList<>();
        job.setSteps(steps);
      }
      currentStep = step;
      steps.add(step);
    }

    @Override
    public void addEncodingInput(Coder<?> coder) {
      CloudObject encoding = SerializableUtils.ensureSerializable(coder);
      addObject(getProperties(), PropertyNames.ENCODING, encoding);
    }

    @Override
    public void addInput(String name, Boolean value) {
      addBoolean(getProperties(), name, value);
    }

    @Override
    public void addInput(String name, String value) {
      addString(getProperties(), name, value);
    }

    @Override
    public void addInput(String name, Long value) {
      addLong(getProperties(), name, value);
    }

    @Override
    public void addInput(String name, Map<String, Object> elements) {
      addDictionary(getProperties(), name, elements);
    }

    @Override
    public void addInput(String name, List<? extends Map<String, Object>> elements) {
      addList(getProperties(), name, elements);
    }

    @Override
    public void addInput(String name, PInput value) {
      if (value instanceof PValue) {
        addInput(name, asOutputReference((PValue) value));
      } else {
        throw new IllegalStateException("Input must be a PValue");
      }
    }

    @Override
    public void addOutput(String name, PValue value) {
      Coder<?> coder;
      if (value instanceof TypedPValue) {
        coder = ((TypedPValue<?>) value).getCoder();
        if (value instanceof PCollection) {
          // Wrap the PCollection element Coder inside a WindowedValueCoder.
          coder = WindowedValue.getFullCoder(
              coder,
              ((PCollection<?>) value).getWindowingStrategy().getWindowFn().windowCoder());
        }
      } else {
        // No output coder to encode.
        coder = null;
      }
      addOutput(name, value, coder);
    }

    @Override
    public void addValueOnlyOutput(String name, PValue value) {
      Coder<?> coder;
      if (value instanceof TypedPValue) {
        coder = ((TypedPValue<?>) value).getCoder();
        if (value instanceof PCollection) {
          // Wrap the PCollection element Coder inside a ValueOnly
          // WindowedValueCoder.
          coder = WindowedValue.getValueOnlyCoder(coder);
        }
      } else {
        // No output coder to encode.
        coder = null;
      }
      addOutput(name, value, coder);
    }

    @Override
    public void addCollectionToSingletonOutput(String name,
                                               PValue inputValue,
                                               PValue outputValue) {
      Coder<?> inputValueCoder =
          Preconditions.checkNotNull(outputCoders.get(inputValue));
      // The inputValueCoder for the input PCollection should be some
      // WindowedValueCoder of the input PCollection's element
      // coder.
      Preconditions.checkState(
          inputValueCoder instanceof WindowedValue.WindowedValueCoder);
      // The outputValueCoder for the output should be an
      // IterableCoder of the inputValueCoder. This is a property
      // of the backend "CollectionToSingleton" step.
      Coder<?> outputValueCoder = IterableCoder.of(inputValueCoder);
      addOutput(name, outputValue, outputValueCoder);
    }

    /**
     * Adds an output with the given name to the previously added
     * Dataflow step, producing the specified output {@code PValue}
     * with the given {@code Coder} (if not {@code null}).
     */
    private void addOutput(String name, PValue value, Coder<?> valueCoder) {
      registerOutputName(value, name);

      Map<String, Object> properties = getProperties();
      @Nullable List<Map<String, Object>> outputInfoList = null;
      try {
        // TODO: This should be done via a Structs accessor.
        outputInfoList = (List<Map<String, Object>>) properties.get(PropertyNames.OUTPUT_INFO);
      } catch (Exception e) {
        throw new RuntimeException("Inconsistent dataflow pipeline translation", e);
      }
      if (outputInfoList == null) {
        outputInfoList = new ArrayList<>();
        // TODO: This should be done via a Structs accessor.
        properties.put(PropertyNames.OUTPUT_INFO, outputInfoList);
      }

      Map<String, Object> outputInfo = new HashMap<>();
      addString(outputInfo, PropertyNames.OUTPUT_NAME, name);
      addString(outputInfo, PropertyNames.USER_NAME, value.getName());

      if (valueCoder != null) {
        // Verify that encoding can be decoded, in order to catch serialization
        // failures as early as possible.
        CloudObject encoding = SerializableUtils.ensureSerializable(valueCoder);
        addObject(outputInfo, PropertyNames.ENCODING, encoding);
        outputCoders.put(value, valueCoder);
      }

      outputInfoList.add(outputInfo);
    }

    @Override
    public OutputReference asOutputReference(PValue value) {
      AppliedPTransform<?, ?, ?> transform =
          value.getProducingTransformInternal();
      String stepName = stepNames.get(transform);
      if (stepName == null) {
        throw new IllegalArgumentException(transform + " doesn't have a name specified");
      }

      String outputName = outputNames.get(value);
      if (outputName == null) {
        throw new IllegalArgumentException(
            "output " + value + " doesn't have a name specified");
      }

      return new OutputReference(stepName, outputName);
    }

    private Map<String, Object> getProperties() {
      Map<String, Object> properties = currentStep.getProperties();
      if (properties == null) {
        properties = new HashMap<>();
        currentStep.setProperties(properties);
      }
      return properties;
    }

    /**
     * Returns a fresh Dataflow step name.
     */
    private String genStepName() {
      return "s" + (stepNames.size() + 1);
    }

    /**
     * Records the name of the given output PValue,
     * within its producing transform.
     */
    private void registerOutputName(POutput value, String name) {
      if (outputNames.put(value, name) != null) {
        throw new IllegalArgumentException(
            "output " + value + " already has a name specified");
      }
    }
  }

  /////////////////////////////////////////////////////////////////////////////

  @Override
  public String toString() {
    return "DataflowPipelineTranslator#" + hashCode();
  }


  ///////////////////////////////////////////////////////////////////////////

  static {
    registerTransformTranslator(
        View.CreatePCollectionView.class,
        new TransformTranslator<View.CreatePCollectionView>() {
          @Override
          public void translate(
              View.CreatePCollectionView transform,
              TranslationContext context) {
            translateTyped(transform, context);
          }

          private <ElemT, ViewT> void translateTyped(
              View.CreatePCollectionView<ElemT, ViewT> transform,
              TranslationContext context) {
            context.addStep(transform, "CollectionToSingleton");
            context.addInput(PropertyNames.PARALLEL_INPUT, context.getInput(transform));
            context.addCollectionToSingletonOutput(
                PropertyNames.OUTPUT,
                context.getInput(transform),
                context.getOutput(transform));
          }
        });

    DataflowPipelineTranslator.registerTransformTranslator(
        Combine.GroupedValues.class,
        new DataflowPipelineTranslator.TransformTranslator<Combine.GroupedValues>() {
          @Override
          public void translate(
              Combine.GroupedValues transform,
              DataflowPipelineTranslator.TranslationContext context) {
            translateHelper(transform, context);
          }

          private <K, InputT, OutputT> void translateHelper(
              final Combine.GroupedValues<K, InputT, OutputT> transform,
              DataflowPipelineTranslator.TranslationContext context) {
            context.addStep(transform, "CombineValues");
            context.addInput(PropertyNames.PARALLEL_INPUT, context.getInput(transform));
            context.addInput(
                PropertyNames.SERIALIZED_FN,
                byteArrayToJsonString(serializeToByteArray(transform.getFn())));
            try {
              context.addEncodingInput(transform.getAccumulatorCoder(
                  context.getInput(transform).getPipeline().getCoderRegistry(),
                  context.getInput(transform)));
            } catch (CannotProvideCoderException exc) {
              throw new IllegalStateException(
                "Could not determine coder for input to Combine.GroupedValues", exc);
            }
            context.addOutput(PropertyNames.OUTPUT, context.getOutput(transform));
          }
        });

    registerTransformTranslator(
        Create.Values.class,
        new TransformTranslator<Create.Values>() {
          @Override
          public void translate(
              Create.Values transform,
              TranslationContext context) {
            createHelper(transform, context);
          }

          private <T> void createHelper(
              Create.Values<T> transform,
              TranslationContext context) {
            context.addStep(transform, "CreateCollection");

            Coder<T> coder = context.getOutput(transform).getCoder();
            List<CloudObject> elements = new LinkedList<>();
            for (T elem : transform.getElements()) {
              byte[] encodedBytes;
              try {
                encodedBytes = encodeToByteArray(coder, elem);
              } catch (CoderException exn) {
                // TODO: Put in better element printing:
                // truncate if too long.
                throw new IllegalArgumentException(
                    "Unable to encode element '" + elem + "' of transform '" + transform
                    + "' using coder '" + coder + "'.",
                    exn);
              }
              String encodedJson = byteArrayToJsonString(encodedBytes);
              assert Arrays.equals(encodedBytes,
                                   jsonStringToByteArray(encodedJson));
              elements.add(CloudObject.forString(encodedJson));
            }
            context.addInput(PropertyNames.ELEMENT, elements);
            context.addValueOnlyOutput(PropertyNames.OUTPUT, context.getOutput(transform));
          }
        });

    registerTransformTranslator(
        Flatten.FlattenPCollectionList.class,
        new TransformTranslator<Flatten.FlattenPCollectionList>() {
          @Override
          public void translate(
              Flatten.FlattenPCollectionList transform,
              TranslationContext context) {
            flattenHelper(transform, context);
          }

          private <T> void flattenHelper(
              Flatten.FlattenPCollectionList<T> transform,
              TranslationContext context) {
            context.addStep(transform, "Flatten");

            List<OutputReference> inputs = new LinkedList<>();
            for (PCollection<T> input : context.getInput(transform).getAll()) {
              inputs.add(context.asOutputReference(input));
            }
            context.addInput(PropertyNames.INPUTS, inputs);
            context.addOutput(PropertyNames.OUTPUT, context.getOutput(transform));
          }
        });

    registerTransformTranslator(
        GroupByKey.class,
        new TransformTranslator<GroupByKey>() {
          @Override
          public void translate(
              GroupByKey transform,
              TranslationContext context) {
            groupByKeyHelper(transform, context);
          }

          private <K, V> void groupByKeyHelper(
              GroupByKey<K, V> transform,
              TranslationContext context) {
            context.addStep(transform, "GroupByKey");
            context.addInput(PropertyNames.PARALLEL_INPUT, context.getInput(transform));
            context.addOutput(PropertyNames.OUTPUT, context.getOutput(transform));

            WindowingStrategy<?, ?> windowingStrategy =
                context.getInput(transform).getWindowingStrategy();
            boolean isStreaming =
                context.getPipelineOptions().as(StreamingOptions.class).isStreaming();
            boolean disallowCombinerLifting =
                !windowingStrategy.getWindowFn().isNonMerging()
                || (isStreaming && !transform.fewKeys())
                // TODO: Allow combiner lifting on the non-default trigger, as appropriate.
                || !(windowingStrategy.getTrigger().getSpec() instanceof DefaultTrigger);
            context.addInput(
                PropertyNames.DISALLOW_COMBINER_LIFTING, disallowCombinerLifting);
            context.addInput(
                PropertyNames.SERIALIZED_FN,
                byteArrayToJsonString(serializeToByteArray(windowingStrategy)));
          }
        });

    registerTransformTranslator(
        ParDo.BoundMulti.class,
        new TransformTranslator<ParDo.BoundMulti>() {
          @Override
          public void translate(
              ParDo.BoundMulti transform,
              TranslationContext context) {
            translateMultiHelper(transform, context);
          }

          private <InputT, OutputT> void translateMultiHelper(
              ParDo.BoundMulti<InputT, OutputT> transform,
              TranslationContext context) {
            context.addStep(transform, "ParallelDo");
            translateInputs(context.getInput(transform), transform.getSideInputs(), context);
            translateFn(transform.getFn(), context.getInput(transform).getWindowingStrategy(),
                transform.getSideInputs(), context.getInput(transform).getCoder(), context);
            translateOutputs(context.getOutput(transform), context);
          }
        });

    registerTransformTranslator(
        ParDo.Bound.class,
        new TransformTranslator<ParDo.Bound>() {
          @Override
          public void translate(
              ParDo.Bound transform,
              TranslationContext context) {
            translateSingleHelper(transform, context);
          }

          private <InputT, OutputT> void translateSingleHelper(
              ParDo.Bound<InputT, OutputT> transform,
              TranslationContext context) {
            context.addStep(transform, "ParallelDo");
            translateInputs(context.getInput(transform), transform.getSideInputs(), context);
            translateFn(
                transform.getFn(),
                context.getInput(transform).getWindowingStrategy(),
                transform.getSideInputs(), context.getInput(transform).getCoder(), context);
            context.addOutput("out", context.getOutput(transform));
          }
        });


    registerTransformTranslator(
        Window.Bound.class,
        new DataflowPipelineTranslator.TransformTranslator<Window.Bound>() {
          @Override
          public void translate(
              Window.Bound transform, TranslationContext context) {
            translateHelper(transform, context);
          }

          private <T> void translateHelper(
              Window.Bound<T> transform, TranslationContext context) {
            context.addStep(transform, "Bucket");
            context.addInput(PropertyNames.PARALLEL_INPUT, context.getInput(transform));
            context.addOutput(PropertyNames.OUTPUT, context.getOutput(transform));

            WindowingStrategy<?, ?> strategy = context.getOutput(transform).getWindowingStrategy();
            byte[] serializedBytes = serializeToByteArray(strategy);
            String serializedJson = byteArrayToJsonString(serializedBytes);
            assert Arrays.equals(serializedBytes,
                                 jsonStringToByteArray(serializedJson));
            context.addInput(PropertyNames.SERIALIZED_FN, serializedJson);
          }
        });

    ///////////////////////////////////////////////////////////////////////////
    // IO Translation.

    registerTransformTranslator(
        AvroIO.Read.Bound.class, new AvroIOTranslator.ReadTranslator());
    registerTransformTranslator(
        AvroIO.Write.Bound.class, new AvroIOTranslator.WriteTranslator());

    registerTransformTranslator(
        BigQueryIO.Read.Bound.class, new BigQueryIOTranslator.ReadTranslator());
    registerTransformTranslator(
        BigQueryIO.Write.Bound.class, new BigQueryIOTranslator.WriteTranslator());

    registerTransformTranslator(
        PubsubIO.Read.Bound.class, new PubsubIOTranslator.ReadTranslator());
    registerTransformTranslator(
        DataflowPipelineRunner.StreamingPubsubIOWrite.class,
        new PubsubIOTranslator.WriteTranslator());

    registerTransformTranslator(
        TextIO.Read.Bound.class, new TextIOTranslator.ReadTranslator());
    registerTransformTranslator(
        TextIO.Write.Bound.class, new TextIOTranslator.WriteTranslator());

    registerTransformTranslator(Read.Bounded.class, new ReadTranslator());
  }

  private static void translateInputs(
      PCollection<?> input,
      List<PCollectionView<?>> sideInputs,
      TranslationContext context) {
    context.addInput(PropertyNames.PARALLEL_INPUT, input);
    translateSideInputs(sideInputs, context);
  }

  // Used for ParDo
  private static void translateSideInputs(
      List<PCollectionView<?>> sideInputs,
      TranslationContext context) {
    Map<String, Object> nonParInputs = new HashMap<>();

    for (PCollectionView<?> view : sideInputs) {
      nonParInputs.put(
          view.getTagInternal().getId(),
          context.asOutputReference(view));
    }

    context.addInput(PropertyNames.NON_PARALLEL_INPUTS, nonParInputs);
  }

  private static void translateFn(
      DoFn fn,
      WindowingStrategy windowingStrategy,
      Iterable<PCollectionView<?>> sideInputs,
      Coder inputCoder,
      TranslationContext context) {
    context.addInput(PropertyNames.USER_FN, fn.getClass().getName());
    context.addInput(
        PropertyNames.SERIALIZED_FN,
        byteArrayToJsonString(serializeToByteArray(
            new DoFnInfo(fn, windowingStrategy, sideInputs, inputCoder))));
  }

  private static void translateOutputs(
      PCollectionTuple outputs,
      TranslationContext context) {
    for (Map.Entry<TupleTag<?>, PCollection<?>> entry
             : outputs.getAll().entrySet()) {
      TupleTag<?> tag = entry.getKey();
      PCollection<?> output = entry.getValue();
      context.addOutput(tag.getId(), output);
    }
  }
}
