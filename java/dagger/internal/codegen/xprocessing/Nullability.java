/*
 * Copyright (C) 2023 The Dagger Authors.
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

package dagger.internal.codegen.xprocessing;

import static androidx.room.compiler.processing.XElementKt.isMethod;
import static androidx.room.compiler.processing.XElementKt.isVariableElement;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableSet;
import static dagger.internal.codegen.xprocessing.XElements.asMethod;
import static dagger.internal.codegen.xprocessing.XElements.asVariable;

import androidx.room.compiler.processing.XAnnotated;
import androidx.room.compiler.processing.XElement;
import androidx.room.compiler.processing.XNullability;
import androidx.room.compiler.processing.XType;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.squareup.javapoet.ClassName;
import java.util.Optional;

/**
 * Contains information about the nullability of an element or type.
 *
 * <p>Note that an element can be nullable if either:
 *
 * <ul>
 *   <li>The element is annotated with {@code Nullable} or
 *   <li>the associated kotlin type is nullable (i.e. {@code T?} types in Kotlin source).
 * </ul>
 */
@AutoValue
public abstract class Nullability {
  /** A constant that can represent any non-null element. */
  public static final Nullability NOT_NULLABLE =
      new AutoValue_Nullability(ImmutableSet.of(), ImmutableSet.of(), false);

  public static Nullability of(XElement element) {
    ImmutableSet<ClassName> nonTypeUseNullableAnnotations = getNullableAnnotations(element);
    Optional<XType> type = getType(element);
    ImmutableSet<ClassName> typeUseNullableAnnotations =
    ImmutableSet.of();
    boolean isKotlinTypeNullable =
        // Note: Technically, it isn't possible for Java sources to have nullable types like in
        // Kotlin sources, but for some reason KSP treats certain types as nullable if they have a
        // specific @Nullable (TYPE_USE target) annotation. Thus, to avoid inconsistencies with
        // KAPT, just ignore type nullability for elements in java sources.
        !element.getClosestMemberContainer().isFromJava()
            && type.isPresent()
            && type.get().getNullability() == XNullability.NULLABLE;
    return new AutoValue_Nullability(
        nonTypeUseNullableAnnotations,
        // Filter type use annotations that are also found on the element as non-type use
        // annotations. This prevents them from being applied twice in some scenarios and just
        // defaults to using them in the way before Dagger supported type use annotations.
        Sets.difference(typeUseNullableAnnotations, nonTypeUseNullableAnnotations).immutableCopy(),
        isKotlinTypeNullable);
  }

  private static ImmutableSet<ClassName> getNullableAnnotations(XAnnotated annotated) {
    return annotated.getAllAnnotations().stream()
        .map(XAnnotations::getClassName)
        .filter(annotation -> annotation.simpleName().contentEquals("Nullable"))
        .collect(toImmutableSet());
  }

  private static Optional<XType> getType(XElement element) {
    if (isMethod(element)) {
      return Optional.of(asMethod(element).getReturnType());
    } else if (isVariableElement(element)) {
      return Optional.of(asVariable(element).getType());
    }
    return Optional.empty();
  }

  public abstract ImmutableSet<ClassName> nonTypeUseNullableAnnotations();

  public abstract ImmutableSet<ClassName> typeUseNullableAnnotations();

  /**
   * Returns {@code true} if the element's type is a Kotlin nullable type, e.g. {@code Foo?}.
   *
   * <p>Note that this method ignores any {@code @Nullable} type annotations and only looks for
   * explicit {@code ?} usages on kotlin types.
   */
  public abstract boolean isKotlinTypeNullable();

  public ImmutableSet<ClassName> nullableAnnotations() {
    return ImmutableSet.<ClassName>builder()
        .addAll(nonTypeUseNullableAnnotations())
        .addAll(typeUseNullableAnnotations()).build();
  }

  public final boolean isNullable() {
    return isKotlinTypeNullable() || !nullableAnnotations().isEmpty();
  }

  Nullability() {}
}
