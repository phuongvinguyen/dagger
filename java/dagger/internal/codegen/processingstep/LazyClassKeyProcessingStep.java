/*
 * Copyright (C) 2024 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen.processingstep;

import static androidx.room.compiler.processing.XElementKt.isTypeElement;
import static java.nio.charset.StandardCharsets.UTF_8;

import androidx.room.compiler.processing.XElement;
import androidx.room.compiler.processing.XFiler;
import androidx.room.compiler.processing.XMethodElement;
import androidx.room.compiler.processing.XProcessingEnv;
import androidx.room.compiler.processing.XTypeElement;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import com.squareup.javapoet.ClassName;
import dagger.internal.codegen.javapoet.TypeNames;
import dagger.internal.codegen.writing.LazyMapKeyProxyGenerator;
import dagger.internal.codegen.xprocessing.XElements;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;

/** Generate keep rules for LazyClassKey referenced classes to prevent class merging. */
final class LazyClassKeyProcessingStep extends TypeCheckingProcessingStep<XElement> {
  private static final String PROGUARD_KEEP_RULE = "-keep,allowobfuscation,allowshrinking class ";
  private final SetMultimap<ClassName, ClassName> processedElements = LinkedHashMultimap.create();
  private final LazyMapKeyProxyGenerator lazyMapKeyProxyGenerator;

  @Inject
  LazyClassKeyProcessingStep(LazyMapKeyProxyGenerator lazyMapKeyProxyGenerator) {
    this.lazyMapKeyProxyGenerator = lazyMapKeyProxyGenerator;
  }

  @Override
  public ImmutableSet<ClassName> annotationClassNames() {
    return ImmutableSet.of(TypeNames.LAZY_CLASS_KEY);
  }

  @Override
  protected void process(XElement element, ImmutableSet<ClassName> annotations) {
    ClassName lazyClassKey =
        element
            .getAnnotation(TypeNames.LAZY_CLASS_KEY)
            .getAsType("value")
            .getTypeElement()
            .getClassName();
    // No need to fail, since we want to support customized usage of class key annotations.
    // https://github.com/google/dagger/pull/2831
    if (!isMapBinding(element) || !isModuleOrProducerModule(element.getEnclosingElement())) {
      return;
    }
    XTypeElement moduleElement = XElements.asTypeElement(element.getEnclosingElement());
    processedElements.put(moduleElement.getClassName(), lazyClassKey);
    XMethodElement method = XElements.asMethod(element);
    lazyMapKeyProxyGenerator.generate(method);
  }

  private static boolean isMapBinding(XElement element) {
    return element.hasAnnotation(TypeNames.INTO_MAP)
        && (element.hasAnnotation(TypeNames.BINDS)
            || element.hasAnnotation(TypeNames.PROVIDES)
            || element.hasAnnotation(TypeNames.PRODUCES));
  }

  private static boolean isModuleOrProducerModule(XElement element) {
    return isTypeElement(element)
        && (element.hasAnnotation(TypeNames.MODULE)
            || element.hasAnnotation(TypeNames.PRODUCER_MODULE));
  }

  @Override
  public void processOver(
      XProcessingEnv env, Map<String, ? extends Set<? extends XElement>> elementsByAnnotation) {
    super.processOver(env, elementsByAnnotation);
    StringBuilder proguardRules = new StringBuilder();
    for (Map.Entry<ClassName, Collection<ClassName>> moduleToLazyClassKeys :
        processedElements.asMap().entrySet()) {
      String bindingGraphProguardName =
          getFullyQualifiedEnclosedClassName(moduleToLazyClassKeys.getKey()) + "_LazyClassKeys.pro";
      for (ClassName lazyClassKey : moduleToLazyClassKeys.getValue()) {
        proguardRules.append(PROGUARD_KEEP_RULE).append(lazyClassKey).append("\n");
      }
      writeProguardFile(bindingGraphProguardName, proguardRules.toString(), env.getFiler());
    }
  }

  private void writeProguardFile(String proguardFileName, String proguardRules, XFiler filer) {
    try (OutputStream outputStream =
            filer.writeResource(
                Path.of("META-INF/proguard/" + proguardFileName),
                ImmutableList.<XElement>of(),
                XFiler.Mode.Isolating);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, UTF_8))) {
      writer.write(proguardRules);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Returns the fully qualified class name, with _ instead of . */
  private static String getFullyQualifiedEnclosedClassName(ClassName className) {
    return className.packageName().replace('.', '_') + getEnclosedName(className);
  }

  public static String getEnclosedName(ClassName name) {
    return Joiner.on('_').join(name.simpleNames());
  }
}
