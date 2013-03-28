/*
 * Copyright 2013 <a href="mailto:lincolnbaxter@gmail.com">Lincoln Baxter, III</a>
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ocpsoft.rewrite.servlet.config.lifecycle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import org.ocpsoft.common.util.Assert;
import org.ocpsoft.logging.Logger;
import org.ocpsoft.rewrite.bind.Evaluation;
import org.ocpsoft.rewrite.config.CompositeOperation;
import org.ocpsoft.rewrite.config.Condition;
import org.ocpsoft.rewrite.config.ConditionVisit;
import org.ocpsoft.rewrite.config.Configuration;
import org.ocpsoft.rewrite.config.DefaultOperationBuilder;
import org.ocpsoft.rewrite.config.Operation;
import org.ocpsoft.rewrite.config.OperationVisit;
import org.ocpsoft.rewrite.config.ParameterizedCallback;
import org.ocpsoft.rewrite.config.ParameterizedConditionVisitor;
import org.ocpsoft.rewrite.config.ParameterizedOperationVisitor;
import org.ocpsoft.rewrite.config.Rule;
import org.ocpsoft.rewrite.config.RuleBuilder;
import org.ocpsoft.rewrite.context.ContextBase;
import org.ocpsoft.rewrite.context.EvaluationContext;
import org.ocpsoft.rewrite.context.RewriteState;
import org.ocpsoft.rewrite.event.Rewrite;
import org.ocpsoft.rewrite.param.ConfigurableParameter;
import org.ocpsoft.rewrite.param.DefaultParameter;
import org.ocpsoft.rewrite.param.DefaultParameterStore;
import org.ocpsoft.rewrite.param.Parameter;
import org.ocpsoft.rewrite.param.ParameterStore;
import org.ocpsoft.rewrite.param.ParameterValueStore;
import org.ocpsoft.rewrite.param.Parameterized;
import org.ocpsoft.rewrite.servlet.event.BaseRewrite.Flow;
import org.ocpsoft.rewrite.servlet.http.event.HttpServletRewrite;
import org.ocpsoft.rewrite.util.ParameterUtils;
import org.ocpsoft.rewrite.util.Visitor;

/**
 * An {@link Operation} that allows for conditional evaluation of nested {@link Rule} sets.
 * 
 * @author <a href="mailto:lincolnbaxter@gmail.com">Lincoln Baxter, III</a>
 */
public class Subset extends DefaultOperationBuilder implements CompositeOperation, Parameterized
{
   private static Logger log = Logger.getLogger(Subset.class);
   private final Configuration config;

   private Subset(Configuration config)
   {
      Assert.notNull(config, "Configuration must not be null.");
      this.config = config;
   }

   public static Subset evaluate(Configuration config)
   {
      return new Subset(config);
   }

   /*
    * Executors
    */
   @Override
   public void perform(Rewrite rewrite, EvaluationContext context)
   {

      /*
       * Highly optimized loop - for performance reasons. Think before you change this!
       */
      HttpServletRewrite event = (HttpServletRewrite) rewrite;
      List<Rule> cacheable = new ArrayList<Rule>();
      List<Rule> rules = config.getRules();

      final EvaluationContextImpl subContext = new EvaluationContextImpl();
      for (int i = 0; i < rules.size(); i++)
      {
         Rule rule = rules.get(i);

         subContext.clear();
         subContext.put(ParameterStore.class, context.get(ParameterStore.class));
         ParameterValueStore values = (ParameterValueStore) context.get(ParameterValueStore.class);
         subContext.put(ParameterValueStore.class, values);

         if (rule.evaluate(event, subContext))
         {
            if (!handleBindings(event, subContext, values))
               break;

            log.debug("Rule [" + rule + "] matched and will be performed.");
            cacheable.add(rule);
            List<Operation> preOperations = subContext.getPreOperations();
            for (int k = 0; k < preOperations.size(); k++) {
               preOperations.get(k).perform(event, subContext);
            }

            if (event.getFlow().is(Flow.HANDLED))
            {
               break;
            }

            rule.perform(event, subContext);

            if (event.getFlow().is(Flow.HANDLED))
            {
               break;
            }

            List<Operation> postOperations = subContext.getPostOperations();
            for (int k = 0; k < postOperations.size(); k++) {
               postOperations.get(k).perform(event, subContext);
            }

            if (event.getFlow().is(Flow.HANDLED))
            {
               break;
            }
         }
      }
   }

   private boolean handleBindings(final HttpServletRewrite event, final EvaluationContextImpl context,
            ParameterValueStore values)
   {
      boolean result = true;
      ParameterStore store = (ParameterStore) context.get(ParameterStore.class);

      for (Entry<String, Parameter<?>> entry : store) {
         Parameter<?> parameter = entry.getValue();
         String value = values.retrieve(parameter);

         if (!ParameterUtils.enqueueSubmission(event, context, parameter, value))
         {
            result = false;
            break;
         }
      }
      return result;
   }

   /*
    * Getters
    */

   @Override
   public List<Operation> getOperations()
   {
      return Collections.emptyList();
   }

   class EvaluationContextImpl extends ContextBase implements EvaluationContext
   {
      private final List<Operation> preOperations = new ArrayList<Operation>();
      private final List<Operation> postOperations = new ArrayList<Operation>();

      public EvaluationContextImpl()
      {
         put(ParameterStore.class, new DefaultParameterStore());
      }

      @Override
      public void addPreOperation(final Operation operation)
      {
         this.preOperations.add(operation);
      }

      @Override
      public void addPostOperation(final Operation operation)
      {
         this.preOperations.add(operation);
      }

      /**
       * Get an immutable view of the added pre-{@link Operation} instances.
       */
      public List<Operation> getPreOperations()
      {
         return Collections.unmodifiableList(preOperations);
      }

      /**
       * Get an immutable view of the added post-{@link Operation} instances.
       */
      public List<Operation> getPostOperations()
      {
         return Collections.unmodifiableList(postOperations);
      }

      @Override
      public String toString()
      {
         return "EvaluationContextImpl [preOperations=" + preOperations + ", postOperations=" + postOperations + "]";
      }

      /**
       * Clears the state of this context so that it may be reused, saving instantiation cost during rule iteration.
       */
      public void clear()
      {
         this.postOperations.clear();
         this.postOperations.clear();
      }

      @Override
      public RewriteState getState()
      {
         throw new IllegalStateException("not implemented");
      }
   }

   @Override
   public Set<String> getRequiredParameterNames()
   {
      return Collections.emptySet();
   }

   @Override
   public void setParameterStore(final ParameterStore parent)
   {
      for (final Rule rule : config.getRules()) {
         if (rule instanceof RuleBuilder) {
            ParameterizedCallback callback = new ParameterizedCallback() {
               @Override
               public void call(Parameterized parameterized)
               {
                  Set<String> names = parameterized.getRequiredParameterNames();
                  ParameterStore store = ((RuleBuilder) rule).getParameterStore();

                  for (Entry<String, Parameter<?>> entry : parent) {
                     if (!store.contains(entry.getKey()))
                        store.get(entry.getKey(), entry.getValue());
                     else if (!"*".equals(entry.getKey()))
                        throw new IllegalStateException("Subset cannot re-configure parameter [" + entry.getKey()
                                 + "] that was configured in parent Configuration. Re-definition was attempted at ["
                                 + rule + "] ");
                  }

                  for (String name : names) {
                     Parameter<?> parameter = store.get(name, new DefaultParameter(name));
                     if (parameter instanceof ConfigurableParameter<?>)
                        ((ConfigurableParameter<?>) parameter).bindsTo(Evaluation.property(name));
                  }

                  parameterized.setParameterStore(store);
               }
            };

            Visitor<Condition> conditionVisitor = new ParameterizedConditionVisitor(callback);
            new ConditionVisit(rule).accept(conditionVisitor);

            Visitor<Operation> operationVisitor = new ParameterizedOperationVisitor(callback);
            new OperationVisit(rule).accept(operationVisitor);
         }
      }
   }
}
