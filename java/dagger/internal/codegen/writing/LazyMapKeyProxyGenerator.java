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

package dagger.internal.codegen.writing;

import static com.squareup.javapoet.TypeSpec.classBuilder;
import static dagger.internal.codegen.binding.MapKeys.KEEP_FIELD_TYPE_FIELD;
import static dagger.internal.codegen.binding.MapKeys.LAZY_CLASS_KEY_NAME_FIELD;
import static dagger.internal.codegen.binding.MapKeys.lazyClassKeyProxyClassName;
import static dagger.internal.codegen.javapoet.AnnotationSpecs.Suppression.CAST;
import static dagger.internal.codegen.javapoet.AnnotationSpecs.Suppression.DEPRECATION;
import static dagger.internal.codegen.javapoet.AnnotationSpecs.Suppression.KOTLIN_INTERNAL;
import static dagger.internal.codegen.javapoet.AnnotationSpecs.Suppression.RAWTYPES;
import static dagger.internal.codegen.javapoet.AnnotationSpecs.Suppression.UNCHECKED;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

import androidx.room.compiler.processing.XFiler;
import androidx.room.compiler.processing.XMethodElement;
import androidx.room.compiler.processing.XProcessingEnv;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import dagger.internal.DaggerGenerated;
import dagger.internal.codegen.javapoet.AnnotationSpecs;
import dagger.internal.codegen.javapoet.AnnotationSpecs.Suppression;
import dagger.internal.codegen.javapoet.TypeNames;
import java.util.Optional;
import javax.inject.Inject;

/**
 * Generate a class containing fields that works with proguard rules to support @LazyClassKey
 * usages.
 */
public final class LazyMapKeyProxyGenerator {
  private static final String GENERATED_COMMENTS = "https://dagger.dev";
  private final XProcessingEnv processingEnv;
  private final XFiler filer;

  @Inject
  LazyMapKeyProxyGenerator(XProcessingEnv processingEnv, XFiler filer) {
    this.processingEnv = processingEnv;
    this.filer = filer;
  }

  public void generate(XMethodElement element) {
    ClassName lazyClassKeyProxyClassName = lazyClassKeyProxyClassName(element);
    TypeSpec.Builder typeSpecBuilder =
        classBuilder(lazyClassKeyProxyClassName)
            .addModifiers(PUBLIC, FINAL)
            .addAnnotation(TypeNames.IDENTIFIER_NAME_STRING)
            .addAnnotation(DaggerGenerated.class)
            .addFields(lazyClassKeyFields(element));
    Optional<AnnotationSpec> generatedAnnotation =
        Optional.ofNullable(processingEnv.findGeneratedAnnotation())
            .map(
                annotation ->
                    AnnotationSpec.builder(annotation.getClassName())
                        .addMember("value", "$S", "dagger.internal.codegen.LazyClassKeyProcessor")
                        .addMember("comments", "$S", GENERATED_COMMENTS)
                        .build());
    generatedAnnotation.ifPresent(typeSpecBuilder::addAnnotation);
    typeSpecBuilder.addAnnotation(
        AnnotationSpecs.suppressWarnings(
            ImmutableSet.<Suppression>builder()
                .add(UNCHECKED, RAWTYPES, KOTLIN_INTERNAL, CAST, DEPRECATION)
                .build()));

    filer.write(
        JavaFile.builder(lazyClassKeyProxyClassName.packageName(), typeSpecBuilder.build()).build(),
        XFiler.Mode.Isolating);
  }

  private static ImmutableList<FieldSpec> lazyClassKeyFields(XMethodElement element) {
    ClassName lazyClassMapKeyClassName =
        element
            .getAnnotation(TypeNames.LAZY_CLASS_KEY)
            .getAsType("value")
            .getTypeElement()
            .getClassName();
    // Generate a string referencing the map key class name, and dagger will apply
    // identifierrnamestring rule to it to make sure it is correctly obfuscated.
    FieldSpec lazyClassKeyField =
        FieldSpec.builder(TypeNames.STRING, LAZY_CLASS_KEY_NAME_FIELD)
            // TODO(b/217435141): Leave the field as non-final. We will apply
            // @IdentifierNameString on the field, which doesn't work well with static final
            // fields.
            .addModifiers(STATIC, PUBLIC)
            .initializer("$S", lazyClassMapKeyClassName.reflectionName())
            .build();
    // In proguard, we need to keep the classes referenced by @LazyClassKey, we do that by
    // generating a field referencing the type, and then applying @KeepFieldType to the
    // field. Here, we generate the field in the proxy class. For classes that are
    // accessible from the dagger component, we generate fields in LazyClassKeyProvider.
    // Note: the generated field should not be initialized to avoid class loading.
    FieldSpec keepFieldTypeField =
        FieldSpec.builder(lazyClassMapKeyClassName, KEEP_FIELD_TYPE_FIELD)
            .addModifiers(STATIC)
            .addAnnotation(TypeNames.KEEP_FIELD_TYPE)
            .build();
    return ImmutableList.of(keepFieldTypeField, lazyClassKeyField);
  }
}
