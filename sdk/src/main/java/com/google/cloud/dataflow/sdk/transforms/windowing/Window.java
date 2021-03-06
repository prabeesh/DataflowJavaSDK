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

package com.google.cloud.dataflow.sdk.transforms.windowing;

import com.google.cloud.dataflow.sdk.annotations.Experimental;
import com.google.cloud.dataflow.sdk.annotations.Experimental.Kind;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.Coder.NonDeterministicException;
import com.google.cloud.dataflow.sdk.runners.DirectPipelineRunner;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.GroupByKey;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.util.AssignWindowsDoFn;
import com.google.cloud.dataflow.sdk.util.DirectModeExecutionContext;
import com.google.cloud.dataflow.sdk.util.DoFnRunner;
import com.google.cloud.dataflow.sdk.util.NullSideInputReader;
import com.google.cloud.dataflow.sdk.util.WindowedValue;
import com.google.cloud.dataflow.sdk.util.WindowingStrategy;
import com.google.cloud.dataflow.sdk.util.WindowingStrategy.AccumulationMode;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.TupleTag;

import org.joda.time.Duration;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * {@code Window} logically divides up or groups the elements of a
 * {@link PCollection} into finite windows according to a {@link WindowFn}.
 * The output of {@code Window} contains the same elements as input, but they
 * have been logically assigned to windows. The next
 * {@link com.google.cloud.dataflow.sdk.transforms.GroupByKey GroupByKeys},
 * including one within composite transforms, will group by the combination of
 * keys and windows.

 * <p> See {@link com.google.cloud.dataflow.sdk.transforms.GroupByKey}
 * for more information about how grouping with windows works.
 *
 * <h2> Windowing </h2>
 *
 * <p> Windowing a {@code PCollection} divides the elements into windows based
 * on the associated event time for each element. This is especially useful
 * for {@code PCollection}s with unbounded size, since it allows operating on
 * a sub-group of the elements placed into a related window. For {@code PCollection}s
 * with a bounded size (aka. conventional batch mode), by default, all data is
 * implicitly in a single window, unless {@code Window} is applied.
 *
 * <p> For example, a simple form of windowing divides up the data into
 * fixed-width time intervals, using {@link FixedWindows}.
 * The following example demonstrates how to use {@code Window} in a pipeline
 * that counts the number of occurrences of strings each minute:
 *
 * <pre> {@code
 * PCollection<String> items = ...;
 * PCollection<String> windowed_items = items.apply(
 *   Window.<String>into(FixedWindows.of(Duration.standardMinutes(1))));
 * PCollection<KV<String, Long>> windowed_counts = windowed_items.apply(
 *   Count.<String>perElement());
 * } </pre>
 *
 * <p> Let (data, timestamp) denote a data element along with its timestamp.
 * Then, if the input to this pipeline consists of
 * {("foo", 15s), ("bar", 30s), ("foo", 45s), ("foo", 1m30s)},
 * the output will be
 * {(KV("foo", 2), 1m), (KV("bar", 1), 1m), (KV("foo", 1), 2m)}
 *
 * <p> Several predefined {@link WindowFn}s are provided:
 * <ul>
 *  <li> {@link FixedWindows} partitions the timestamps into fixed-width intervals.
 *  <li> {@link SlidingWindows} places data into overlapping fixed-width intervals.
 *  <li> {@link Sessions} groups data into sessions where each item in a window
 *       is separated from the next by no more than a specified gap.
 * </ul>
 *
 * <p>Additionally, custom {@link WindowFn}s can be created, by creating new
 * subclasses of {@link WindowFn}.
 *
 * <h2> Triggers </h2>
 *
 * <p> {@link Window.Bound#triggering(Trigger)} allows specifying a trigger to control when
 * (in processing time) results for the given window can be produced. If unspecified, the default
 * behavior is to trigger first when the watermark passes the end of the window, and then trigger
 * again every time there is late arriving data.
 *
 * <p> Elements are added to the current window pane as they arrive. When the root trigger fires,
 * output is produced based on the elements in the current pane.
 *
 * <p>Depending on the trigger, this can be used both to output partial results
 * early during the processing of the whole window, and to deal with late
 * arriving in batches.
 *
 * <p> Continuing the earlier example, if we wanted to emit the values that were available
 * when the watermark passed the end of the window, and then output any late arriving
 * elements once-per (actual hour) hour until we have finished processing the next 24-hours of data.
 * (The use of watermark time to stop processing tends to be more robust if the data source is slow
 * for a few days, etc.)
 *
 * <pre> {@code
 * PCollection<String> items = ...;
 * PCollection<String> windowed_items = items.apply(
 *   Window.<String>into(FixedWindows.of(Duration.standardMinutes(1))
 *      .triggering(AfterEach.inOrder(
 *          AfterWatermark.pastEndOfWindow(),
 *          Repeatedly
 *              .forever(AfterProcessingTime
 *                  .pastFirstElementInPane().plusDelay(Duration.standardMinutes(1)))
 *              .orFinally(AfterWatermark
 *                  .pastEndOfWindow().plusDelay(Duration.standardDays(1)))));
 * PCollection<KV<String, Long>> windowed_counts = windowed_items.apply(
 *   Count.<String>perElement());
 * } </pre>
 *
 * <p> On the other hand, if we wanted to get early results every minute of processing
 * time (for which there were new elements in the given window) we could do the following:
 *
 * <pre> {@code
 * PCollection<String> windowed_items = items.apply(
 *   Window.<String>into(FixedWindows.of(Duration.standardMinutes(1))
 *      .triggering(Repeatedly
 *              .forever(AfterProcessingTime
 *                  .pastFirstElementInPane().plusDelay(Duration.standardMinutes(1)))
 *              .orFinally(AfterWatermark.pastEndOfWindow())));
 * } </pre>
 *
 * <p> After a {@link com.google.cloud.dataflow.sdk.transforms.GroupByKey} the trigger is reset to
 * the default trigger. If you want to produce early results from a pipeline consisting of multiple
 * {@code GroupByKey}s, you must set a trigger before <i>each</i> {@code GroupByKey}.
 *
 * <p> See {@link Trigger} for details on the available triggers.
 */
public class Window {
  /**
   * Creates a {@code Window} {@code PTransform} with the given name.
   *
   * <p> See the discussion of Naming in
   * {@link com.google.cloud.dataflow.sdk.transforms.ParDo} for more explanation.
   *
   * <p> The resulting {@code PTransform} is incomplete, and its input/output
   * type is not yet bound.  Use {@link Window.Unbound#into} to specify the
   * {@link WindowFn} to use, which will also bind the input/output type of this
   * {@code PTransform}.
   */
  public static Unbound named(String name) {
    return new Unbound().named(name);
  }

  /**
   * Creates a {@code Window} {@code PTransform} that uses the given
   * {@link WindowFn} to window the data.
   *
   * <p> The resulting {@code PTransform}'s types have been bound, with both the
   * input and output being a {@code PCollection<T>}, inferred from the types of
   * the argument {@code WindowFn<T, B>}.  It is ready to be applied, or further
   * properties can be set on it first.
   */
  public static <T> Bound<T> into(WindowFn<? super T, ?> fn) {
    return new Unbound().into(fn);
  }

  /**
   * Sets a non-default trigger for this {@code Window} {@code PTransform}.
   * Elements that are assigned to a specific window will be output when
   * the trigger fires.
   *
   * <p> Must also specify allowed lateness using {@link #withAllowedLateness} and accumulation
   * mode using either {@link #discardingFiredPanes()} or {@link #accumulatingFiredPanes()}.
   */
  @Experimental(Kind.TRIGGER)
  public static <T> Bound<T> triggering(Trigger<?> trigger) {
    return new Unbound().triggering(trigger);
  }

  /**
   * Returns a new {@code Window} {@code PTransform} that uses the registered WindowFn and
   * Triggering behavior, and that discards elements in a pane after they are triggered.
   *
   * <p> Does not modify this transform.  The resulting {@code PTransform} is sufficiently
   * specified to be applied, but more properties can still be specified.
   */
  @Experimental(Kind.TRIGGER)
  public static <T> Bound<T> discardingFiredPanes() {
    return new Unbound().discardingFiredPanes();
  }

  /**
   * Returns a new {@code Window} {@code PTransform} that uses the registered WindowFn and
   * Triggering behavior, and that accumulates elements in a pane after they are triggered.
   *
   * <p> Does not modify this transform.  The resulting {@code PTransform} is sufficiently
   * specified to be applied, but more properties can still be specified.
   */
  @Experimental(Kind.TRIGGER)
  public static <T> Bound<T> accumulatingFiredPanes() {
    return new Unbound().accumulatingFiredPanes();
  }

  /**
   * Override the amount of lateness allowed for data elements in the pipeline. Like
   * the other properties on this {@link Window} operation, this will be applied at
   * the next {@link GroupByKey}. Any elements that are later than this as decided by
   * the system-maintained watermark will be dropped.
   *
   * <p>This value also determines how long state will be kept around for old windows.
   * Once no elements will be added to a window (because this duration has passed) any state
   * associated with the window will be cleaned up.
   */
  @Experimental(Kind.TRIGGER)
  public static <T> Bound<T> withAllowedLateness(Duration allowedLateness) {
    return new Unbound().withAllowedLateness(allowedLateness);
  }

  /**
   * An incomplete {@code Window} transform, with unbound input/output type.
   *
   * <p> Before being applied, {@link Window.Unbound#into} must be
   * invoked to specify the {@link WindowFn} to invoke, which will also
   * bind the input/output type of this {@code PTransform}.
   */
  public static class Unbound {
    String name;

    Unbound() {}

    Unbound(String name) {
      this.name = name;
    }

    /**
     * Returns a new {@code Window} transform that's like this
     * transform but with the specified name.  Does not modify this
     * transform.  The resulting transform is still incomplete.
     *
     * <p> See the discussion of Naming in
     * {@link com.google.cloud.dataflow.sdk.transforms.ParDo} for more
     * explanation.
     */
    public Unbound named(String name) {
      return new Unbound(name);
    }

    /**
     * Returns a new {@code Window} {@code PTransform} that's like this
     * transform but that will use the given {@link WindowFn}, and that has
     * its input and output types bound.  Does not modify this transform.  The
     * resulting {@code PTransform} is sufficiently specified to be applied,
     * but more properties can still be specified.
     */
    public <T> Bound<T> into(WindowFn<? super T, ?> fn) {
      return new Bound<T>(name).into(fn);
    }

    /**
     * Sets a non-default trigger for this {@code Window} {@code PTransform}.
     * Elements that are assigned to a specific window will be output when
     * the trigger fires.
     *
     * <p> {@link com.google.cloud.dataflow.sdk.transforms.windowing.Trigger}
     * has more details on the available triggers.
     *
     * <p> Must also specify allowed lateness using {@link #withAllowedLateness} and accumulation
     * mode using either {@link #discardingFiredPanes()} or {@link #accumulatingFiredPanes()}.
     */
    @Experimental(Kind.TRIGGER)
    public <T> Bound<T> triggering(Trigger<?> trigger) {
      return new Bound<T>(name).triggering(trigger);
    }

    /**
     * Returns a new {@code Window} {@code PTransform} that uses the registered WindowFn and
     * Triggering behavior, and that discards elements in a pane after they are triggered.
     *
     * <p> Does not modify this transform.  The resulting {@code PTransform} is sufficiently
     * specified to be applied, but more properties can still be specified.
     */
    @Experimental(Kind.TRIGGER)
    public <T> Bound<T> discardingFiredPanes() {
      return new Bound<T>(name).discardingFiredPanes();
    }

    /**
     * Returns a new {@code Window} {@code PTransform} that uses the registered WindowFn and
     * Triggering behavior, and that accumulates elements in a pane after they are triggered.
     *
     * <p> Does not modify this transform.  The resulting {@code PTransform} is sufficiently
     * specified to be applied, but more properties can still be specified.
     */
    @Experimental(Kind.TRIGGER)
    public <T> Bound<T> accumulatingFiredPanes() {
      return new Bound<T>(name).accumulatingFiredPanes();
    }

    /**
     * Override the amount of lateness allowed for data elements in the pipeline. Like
     * the other properties on this {@link Window} operation, this will be applied at
     * the next {@link GroupByKey}. Any elements that are later than this as decided by
     * the system-maintained watermark will be dropped.
     *
     * <p>This value also determines how long state will be kept around for old windows.
     * Once no elements will be added to a window (because this duration has passed) any state
     * associated with the window will be cleaned up.
     */
    @Experimental(Kind.TRIGGER)
    public <T> Bound<T> withAllowedLateness(Duration allowedLateness) {
      return new Bound<T>(name).withAllowedLateness(allowedLateness);
    }
  }

  /**
   * A {@code PTransform} that windows the elements of a {@code PCollection<T>},
   * into finite windows according to a user-specified {@code WindowFn<T, B>}.
   *
   * @param <T> The type of elements this {@code Window} is applied to
   */
  @SuppressWarnings("serial")
  public static class Bound<T> extends PTransform<PCollection<T>, PCollection<T>> {

    @Nullable private final WindowFn<? super T, ?> windowFn;
    @Nullable private final Trigger<?> trigger;
    @Nullable private final AccumulationMode mode;
    @Nullable private final Duration allowedLateness;

    private Bound(String name,
        @Nullable WindowFn<? super T, ?> windowFn, @Nullable Trigger<?> trigger,
        @Nullable AccumulationMode mode, @Nullable Duration allowedLateness) {
      super(name);
      this.windowFn = windowFn;
      this.trigger = trigger;
      this.mode = mode;
      this.allowedLateness = allowedLateness;
    }

    private Bound(String name) {
      this(name, null, null, null, null);
    }

    /**
     * Returns a new {@code Window} {@code PTransform} that's like this
     * transform but that will use the given {@link WindowFn}, and that has
     * its input and output types bound.  Does not modify this transform.  The
     * resulting {@code PTransform} is sufficiently specified to be applied,
     * but more properties can still be specified.
     */
    private Bound<T> into(WindowFn<? super T, ?> windowFn) {
      try {
        windowFn.windowCoder().verifyDeterministic();
      } catch (NonDeterministicException e) {
        throw new IllegalArgumentException("Window coders must be deterministic.", e);
      }

      return new Bound<>(name, windowFn, trigger, mode, allowedLateness);
    }

    /**
     * Returns a new {@code Window} {@code PTransform} that's like this
     * {@code PTransform} but with the specified name.  Does not
     * modify this {@code PTransform}.
     *
     * <p> See the discussion of Naming in
     * {@link com.google.cloud.dataflow.sdk.transforms.ParDo} for more
     * explanation.
     */
    public Bound<T> named(String name) {
      return new Bound<>(name, windowFn, trigger, mode, allowedLateness);
    }

    /**
     * Sets a non-default trigger for this {@code Window} {@code PTransform}.
     * Elements that are assigned to a specific window will be output when
     * the trigger fires.
     *
     * <p> {@link com.google.cloud.dataflow.sdk.transforms.windowing.Trigger}
     * has more details on the available triggers.
     *
     * <p> Must also specify allowed lateness using {@link #withAllowedLateness} and accumulation
     * mode using either {@link #discardingFiredPanes()} or {@link #accumulatingFiredPanes()}.
     */
    @Experimental(Kind.TRIGGER)
    public Bound<T> triggering(Trigger<?> trigger) {
      return new Bound<T>(name, windowFn, trigger, mode, allowedLateness);
    }

   /**
    * Returns a new {@code Window} {@code PTransform} that uses the registered WindowFn and
    * Triggering behavior, and that discards elements in a pane after they are triggered.
    *
    * <p> Does not modify this transform.  The resulting {@code PTransform} is sufficiently
    * specified to be applied, but more properties can still be specified.
    */
    @Experimental(Kind.TRIGGER)
   public Bound<T> discardingFiredPanes() {
     return new Bound<T>(name,
         windowFn, trigger, AccumulationMode.DISCARDING_FIRED_PANES, allowedLateness);
   }

   /**
    * Returns a new {@code Window} {@code PTransform} that uses the registered WindowFn and
    * Triggering behavior, and that accumulates elements in a pane after they are triggered.
    *
    * <p> Does not modify this transform.  The resulting {@code PTransform} is sufficiently
    * specified to be applied, but more properties can still be specified.
    */
   @Experimental(Kind.TRIGGER)
   public Bound<T> accumulatingFiredPanes() {
     return new Bound<T>(name,
         windowFn, trigger, AccumulationMode.ACCUMULATING_FIRED_PANES, allowedLateness);
   }

    /**
     * Override the amount of lateness allowed for data elements in the pipeline. Like
     * the other properties on this {@link Window} operation, this will be applied at
     * the next {@link GroupByKey}. Any elements that are later than this as decided by
     * the system-maintained watermark will be dropped.
     *
     * <p>This value also determines how long state will be kept around for old windows.
     * Once no elements will be added to a window (because this duration has passed) any state
     * associated with the window will be cleaned up.
     */
    @Experimental(Kind.TRIGGER)
    public Bound<T> withAllowedLateness(Duration allowedLateness) {
      return new Bound<T>(name, windowFn, trigger, mode, allowedLateness);
    }

    private WindowingStrategy<?, ?> getOutputStrategy(WindowingStrategy<?, ?> inputStrategy) {
      WindowingStrategy<?, ?> result = inputStrategy;
      if (windowFn != null) {
        result = result.withWindowFn(windowFn);
      }
      if (trigger != null) {
        result = result.withTrigger(trigger);
      }
      if (mode != null) {
        result = result.withMode(mode);
      }
      if (allowedLateness != null) {
        result = result.withAllowedLateness(allowedLateness);
      }
      return result;
    }

    @Override
    public void validate(PCollection<T> input) {
      WindowingStrategy<?, ?> outputStrategy = getOutputStrategy(input.getWindowingStrategy());

      // Make sure that the windowing strategy is complete & valid.
      if (outputStrategy.isTriggerSpecified()
          && !(outputStrategy.getTrigger().getSpec() instanceof DefaultTrigger)) {
        if (!outputStrategy.isAllowedLatenessSpecified()) {
          throw new IllegalArgumentException(
              "Calling .triggering() to specify a trigger requires that the allowed lateness be"
              + " specified using .withAllowedLateness() to set the upper bound on how late data"
              + " can arrive before being dropped. See Javadoc for more details.");
        }

        if (!outputStrategy.isModeSpecified()) {
          throw new IllegalArgumentException(
              "Calling .triggering() to specify a trigger requires that the accumulation mode be"
              + " specified using .discardingFiredPanes() or .accumulatingFiredPanes()."
              + " See Javadoc for more details.");
        }
      }
    }

    @Override
    public PCollection<T> apply(PCollection<T> input) {
      WindowingStrategy<?, ?> outputStrategy = getOutputStrategy(input.getWindowingStrategy());
      if (windowFn != null) {
        // If the windowFn changed, we create a primitive, and run the AssignWindows operation here.
        return PCollection.<T>createPrimitiveOutputInternal(
            input.getPipeline(), outputStrategy, input.isBounded());
      } else {
        // If the windowFn didn't change, we just run a pass-through transform and then set the
        // new windowing strategy.
        return input.apply(Window.<T>identity()).setWindowingStrategyInternal(outputStrategy);
      }

    }

    @Override
    protected Coder<?> getDefaultOutputCoder(PCollection<T> input) {
      return input.getCoder();
    }

    @Override
    protected String getKindString() {
      return "Window.Into()";
    }
  }

  /////////////////////////////////////////////////////////////////////////////

  private static <T> PTransform<PCollection<? extends T>, PCollection<T>> identity() {
    return ParDo.named("Identity").of(new DoFn<T, T>() {

      private static final long serialVersionUID = 0L;

      @Override public void processElement(ProcessContext c) {
        c.output(c.element());
      }
    });
  }

  /**
   * Creates a {@code Window} {@code PTransform} that does not change assigned
   * windows, but will cause windows to be merged again as part of the next
   * {@link com.google.cloud.dataflow.sdk.transforms.GroupByKey}.
   */
  public static <T> Remerge<T> remerge() {
    return new Remerge<T>();
  }

  /**
   * {@code PTransform} that does not change assigned windows, but will cause
   *  windows to be merged again as part of the next
   * {@link com.google.cloud.dataflow.sdk.transforms.GroupByKey}.
   */
  @SuppressWarnings("serial")
  public static class Remerge<T> extends PTransform<PCollection<T>, PCollection<T>> {
    @Override
    public PCollection<T> apply(PCollection<T> input) {
      WindowingStrategy<?, ?> outputWindowingStrategy = getOutputWindowing(
          input.getWindowingStrategy());

      return input.apply(Window.<T>identity())
          .setWindowingStrategyInternal(outputWindowingStrategy);
    }

    private <W extends BoundedWindow> WindowingStrategy<?, W> getOutputWindowing(
        WindowingStrategy<?, W> inputStrategy) {
      if (inputStrategy.getWindowFn() instanceof InvalidWindows) {
        @SuppressWarnings("unchecked")
        InvalidWindows<W> invalidWindows = (InvalidWindows<W>) inputStrategy.getWindowFn();
        return inputStrategy.withWindowFn(invalidWindows.getOriginalWindowFn());
      } else {
        return inputStrategy;
      }
    }
  }

  /////////////////////////////////////////////////////////////////////////////

  static {
    DirectPipelineRunner.registerDefaultTransformEvaluator(
        Bound.class,
        new DirectPipelineRunner.TransformEvaluator<Bound>() {
          @Override
          public void evaluate(
              Bound transform,
              DirectPipelineRunner.EvaluationContext context) {
            evaluateHelper(transform, context);
          }
        });
  }

  private static <T, W extends BoundedWindow> void evaluateHelper(
      Bound<T> transform,
      DirectPipelineRunner.EvaluationContext context) {

    // If this use of Window didn't change the WindowFn, there is nothing to do.
    if (transform.windowFn == null) {
      throw new IllegalStateException("Shouldn't reach evaluateHelper with no windowFn");
    }

    PCollection<T> input = context.getInput(transform);

    DirectModeExecutionContext executionContext = DirectModeExecutionContext.create();

    TupleTag<T> outputTag = new TupleTag<>();
    WindowFn<? super T, W> windowFn = (WindowFn<? super T, W>) transform.windowFn;
    String name = context.getStepName(transform);
    @SuppressWarnings("unchecked")
    DoFn<T, T> addWindowsDoFn = new AssignWindowsDoFn<T, W>(windowFn);
    DoFnRunner<T, T, List<WindowedValue<?>>> addWindowsRunner =
        DoFnRunner.create(
            context.getPipelineOptions(),
            addWindowsDoFn,
            NullSideInputReader.empty(),
            new DoFnRunner.ListOutputManager(),
            outputTag,
            new ArrayList<TupleTag<?>>(),
            executionContext.getStepContext(name, name),
            context.getAddCounterMutator(),
            context.getOutput(transform).getWindowingStrategy());

    addWindowsRunner.startBundle();

    // Process input elements.
    for (DirectPipelineRunner.ValueWithMetadata<T> inputElem
             : context.getPCollectionValuesWithMetadata(input)) {
      executionContext.setKey(inputElem.getKey());
      addWindowsRunner.processElement(inputElem.getWindowedValue());
    }

    addWindowsRunner.finishBundle();

    context.setPCollectionValuesWithMetadata(
        context.getOutput(transform),
        executionContext.getOutput(outputTag));
  }
}
