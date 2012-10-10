/*
 * Copyright 2010-2012 JetBrains s.r.o.
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

package org.jetbrains.jet.lang.resolve.java.resolver;

import com.google.common.collect.Lists;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiModifier;
import gnu.trove.THashMap;
import gnu.trove.TObjectHashingStrategy;
import jet.typeinfo.TypeInfoVariance;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.jet.lang.resolve.BindingTrace;
import org.jetbrains.jet.lang.resolve.java.*;
import org.jetbrains.jet.lang.resolve.java.data.ResolverBinaryClassData;
import org.jetbrains.jet.lang.resolve.java.data.ResolverClassData;
import org.jetbrains.jet.lang.resolve.java.data.ResolverSyntheticClassObjectClassData;
import org.jetbrains.jet.lang.resolve.java.descriptor.ClassDescriptorFromJvmBytecode;
import org.jetbrains.jet.lang.resolve.java.kt.JetClassAnnotation;
import org.jetbrains.jet.lang.resolve.java.scope.JavaClassMembersScope;
import org.jetbrains.jet.lang.resolve.java.wrapper.PsiClassWrapper;
import org.jetbrains.jet.lang.resolve.name.FqName;
import org.jetbrains.jet.lang.resolve.name.FqNameBase;
import org.jetbrains.jet.lang.resolve.name.FqNameUnsafe;
import org.jetbrains.jet.lang.resolve.name.Name;
import org.jetbrains.jet.lang.resolve.scopes.RedeclarationHandler;
import org.jetbrains.jet.lang.resolve.scopes.WritableScope;
import org.jetbrains.jet.lang.resolve.scopes.WritableScopeImpl;
import org.jetbrains.jet.lang.types.ErrorUtils;
import org.jetbrains.jet.lang.types.JetType;
import org.jetbrains.jet.lang.types.TypeUtils;
import org.jetbrains.jet.lang.types.lang.JetStandardClasses;
import org.jetbrains.jet.lang.types.lang.JetStandardLibrary;
import org.jetbrains.jet.rt.signature.JetSignatureAdapter;
import org.jetbrains.jet.rt.signature.JetSignatureExceptionsAdapter;
import org.jetbrains.jet.rt.signature.JetSignatureReader;
import org.jetbrains.jet.rt.signature.JetSignatureVisitor;

import javax.inject.Inject;
import java.util.*;

import static org.jetbrains.jet.lang.resolve.DescriptorResolver.createEnumClassObjectValueOfMethod;
import static org.jetbrains.jet.lang.resolve.DescriptorResolver.createEnumClassObjectValuesMethod;
import static org.jetbrains.jet.lang.resolve.DescriptorUtils.getClassObjectName;

public final class JavaClassResolver {

    // NOTE: this complexity is introduced because class descriptors do not always have valid fqnames (class objects)
    private final Map<FqNameBase, ResolverClassData> classDescriptorCache =
            new THashMap<FqNameBase, ResolverClassData>(new TObjectHashingStrategy<FqNameBase>() {
                @Override
                public int computeHashCode(FqNameBase o) {
                    if (o instanceof FqName) {
                        return ((FqName) o).toUnsafe().hashCode();
                    }
                    assert o instanceof FqNameUnsafe;
                    return o.hashCode();
                }

                @Override
                public boolean equals(FqNameBase n1, FqNameBase n2) {
                    return n1.equalsTo(n2.toString()) && n2.equalsTo(n1.toString());
                }
            });

    private BindingTrace trace;
    private JavaDescriptorResolver javaDescriptorResolver;
    private JavaDescriptorSignatureResolver signatureResolver;
    private JavaSemanticServices semanticServices;
    private JavaAnnotationResolver annotationResolver;
    private PsiClassFinder psiClassFinder;
    private JavaNamespaceResolver namespaceResolver;
    private JavaTypeTransformer typeTransformer;

    public JavaClassResolver() {
    }

    @Inject
    public void setTrace(BindingTrace trace) {
        this.trace = trace;
    }

    @Inject
    public void setJavaDescriptorResolver(JavaDescriptorResolver javaDescriptorResolver) {
        this.javaDescriptorResolver = javaDescriptorResolver;
    }

    @Inject
    public void setSignatureResolver(JavaDescriptorSignatureResolver signatureResolver) {
        this.signatureResolver = signatureResolver;
    }

    @Inject
    public void setSemanticServices(JavaSemanticServices semanticServices) {
        this.semanticServices = semanticServices;
    }

    @Inject
    public void setAnnotationResolver(JavaAnnotationResolver annotationResolver) {
        this.annotationResolver = annotationResolver;
    }

    @Inject
    public void setPsiClassFinder(PsiClassFinder psiClassFinder) {
        this.psiClassFinder = psiClassFinder;
    }

    @Inject
    public void setNamespaceResolver(JavaNamespaceResolver namespaceResolver) {
        this.namespaceResolver = namespaceResolver;
    }

    @Inject
    public void setTypeTransformer(JavaTypeTransformer typeTransformer) {
        this.typeTransformer = typeTransformer;
    }

    @Nullable
    public ClassDescriptor resolveClass(@NotNull FqName qualifiedName, @NotNull DescriptorSearchRule searchRule) {
        List<Runnable> tasks = Lists.newArrayList();
        ClassDescriptor clazz = resolveClass(qualifiedName, searchRule, tasks);
        for (Runnable task : tasks) {
            task.run();
        }
        return clazz;
    }

    @Nullable
    public ClassDescriptor resolveClass(
            @NotNull FqName qualifiedName,
            @NotNull DescriptorSearchRule searchRule,
            @NotNull List<Runnable> tasks
    ) {
        if (isTraitImplementation(qualifiedName)) {
            return null;
        }

        ClassDescriptor builtinClassDescriptor = semanticServices.getKotlinBuiltinClassDescriptor(qualifiedName);
        if (builtinClassDescriptor != null) {
            return builtinClassDescriptor;
        }

        // First, let's check that this is a real Java class, not a Java's view on a Kotlin class:
        ClassDescriptor kotlinClassDescriptor = semanticServices.getKotlinClassDescriptor(qualifiedName);
        if (kotlinClassDescriptor != null) {
            return searchRule.processFoundInKotlin(kotlinClassDescriptor);
        }

        // Not let's take a descriptor of a Java class
        ResolverClassData classData = classDescriptorCache.get(qualifiedName);
        if (classData != null) {
            return classData.getClassDescriptor();
        }

        return doResolveClass(qualifiedName, tasks);
    }

    @Nullable
    private ClassDescriptor doResolveClass(@NotNull FqName qualifiedName, List<Runnable> tasks) {
        PsiClass psiClass = psiClassFinder.findPsiClass(qualifiedName, PsiClassFinder.RuntimeClassesHandleMode.THROW);
        if (psiClass == null) {
            cacheValue(qualifiedName);
            return null;
        }
        ResolverClassData classData = createJavaClassDescriptor(qualifiedName, psiClass, tasks);
        return classData.getClassDescriptor();
    }

    private void cacheValue(@NotNull FqName qualifiedName) {
        ResolverClassData oldValue = classDescriptorCache.put(qualifiedName, ResolverBinaryClassData.NEGATIVE);
        if (oldValue != null) {
            throw new IllegalStateException("rewrite at " + qualifiedName);
        }
    }

    private static boolean isTraitImplementation(@NotNull FqName qualifiedName) {
        // TODO: only if -$$TImpl class is created by Kotlin
        return qualifiedName.getFqName().endsWith(JvmAbi.TRAIT_IMPL_SUFFIX);
    }

    @NotNull
    private ResolverClassData createJavaClassDescriptor(
            @NotNull FqName fqName, @NotNull final PsiClass psiClass,
            List<Runnable> taskList
    ) {

        checkFqNamesAreConsistent(psiClass, fqName);
        DescriptorResolverUtils.checkPsiClassIsNotJet(psiClass);

        ClassOrNamespaceDescriptor containingDeclaration = resolveParentDescriptor(psiClass);
        // class may be resolved during resolution of parent
        ResolverClassData classData = classDescriptorCache.get(fqName);
        if (classData != null) {
            return classData;
        }

        return doCreateClassDescriptor(fqName, psiClass, taskList, containingDeclaration);
    }

    @NotNull
    private ResolverClassData doCreateClassDescriptor(
            @NotNull FqName fqName,
            @NotNull PsiClass psiClass,
            @NotNull List<Runnable> taskList,
            @NotNull ClassOrNamespaceDescriptor containingDeclaration
    ) {
        JetClassAnnotation jetClassAnnotation = JetClassAnnotation.get(psiClass);
        ClassKind kind = getClassKind(psiClass, jetClassAnnotation);
        ClassDescriptorFromJvmBytecode classDescriptor
                = new ClassDescriptorFromJvmBytecode(containingDeclaration, kind, psiClass, fqName, javaDescriptorResolver);

        ResolverClassData classData = classDescriptor.getResolverBinaryClassData();
        classDescriptorCache.put(fqName, classData);
        classDescriptor.setName(Name.identifier(psiClass.getName()));

        List<JavaDescriptorSignatureResolver.TypeParameterDescriptorInitialization> typeParameterDescriptorInitializations
                = signatureResolver.createUninitializedClassTypeParameters(psiClass, classData);

        classDescriptor.setTypeParameterDescriptors(getTypeParametersDescriptors(typeParameterDescriptorInitializations));
        List<JetType> supertypes = Lists.newArrayList();
        classDescriptor.setSupertypes(supertypes);
        classDescriptor.setVisibility(DescriptorResolverUtils.resolveVisibility(psiClass, jetClassAnnotation));
        classDescriptor.setModality(resolveModality(psiClass, classData));
        classDescriptor.createTypeConstructor();
        classDescriptor.setScopeForMemberLookup(new JavaClassMembersScope(semanticServices, classData));

        String context = "class " + psiClass.getQualifiedName();
        signatureResolver.initializeTypeParameters(typeParameterDescriptorInitializations, classDescriptor, context);

        // TODO: ugly hack: tests crash if initializeTypeParameters called with class containing proper supertypes
        List<TypeParameterDescriptor> classTypeParameters = classDescriptor.getTypeConstructor().getParameters();
        supertypes.addAll(getSupertypes(new PsiClassWrapper(psiClass), classData, classTypeParameters));

        MutableClassDescriptorLite classObject = createClassObjectDescriptor(classDescriptor, psiClass);
        if (classObject != null) {
            classDescriptor.getBuilder().setClassObjectDescriptor(classObject);
        }

        classDescriptor.setAnnotations(annotationResolver.resolveAnnotations(psiClass, taskList));

        trace.record(BindingContext.CLASS, psiClass, classDescriptor);

        return classData;
    }

    @NotNull
    private static List<TypeParameterDescriptor> getTypeParametersDescriptors(List<JavaDescriptorSignatureResolver.TypeParameterDescriptorInitialization> typeParameterDescriptorInitializations) {
        List<TypeParameterDescriptor> typeParameters = Lists.newArrayList();
        for (JavaDescriptorSignatureResolver.TypeParameterDescriptorInitialization typeParameter : typeParameterDescriptorInitializations) {
            typeParameters.add(typeParameter.getDescriptor());
        }
        return typeParameters;
    }

    @NotNull
    private static Modality resolveModality(@NotNull PsiClass psiClass, @NotNull ResolverClassData classData) {
        if (classData.getClassDescriptor().getKind() == ClassKind.ANNOTATION_CLASS) {
            return Modality.FINAL;
        }
        return Modality.convertFromFlags(
                    psiClass.hasModifierProperty(PsiModifier.ABSTRACT) || psiClass.isInterface(),
                    !psiClass.hasModifierProperty(PsiModifier.FINAL));
    }

    void checkFqNamesAreConsistent(@NotNull PsiClass psiClass, @NotNull FqName desiredFqName) {
        final String qualifiedName = psiClass.getQualifiedName();
        assert qualifiedName != null;

        FqName fqName = new FqName(qualifiedName);
        assert fqName.equals(desiredFqName);
        if (classDescriptorCache.containsKey(fqName)) {
            throw new IllegalStateException(qualifiedName);
        }
    }

    @Nullable
    private MutableClassDescriptorLite createClassObjectDescriptor(
            @NotNull ClassDescriptor containing,
            @NotNull PsiClass psiClass
    ) {
        DescriptorResolverUtils.checkPsiClassIsNotJet(psiClass);

        if (psiClass.isEnum()) {
            return createClassObjectDescriptorForEnum(containing, psiClass);
        }

        if (!DescriptorResolverUtils.isKotlinClass(psiClass)) {
            return null;
        }

        // If there's at least one inner enum, we need to create a class object (to put this enum into)
        for (PsiClass innerClass : psiClass.getInnerClasses()) {
            if (DescriptorResolverUtils.isInnerEnum(innerClass, containing)) {
                return createSyntheticClassObject(containing, psiClass);
            }
        }

        PsiClass classObjectPsiClass = getInnerClassClassObject(psiClass);
        if (classObjectPsiClass == null) {
            return null;
        }

        final String qualifiedName = classObjectPsiClass.getQualifiedName();
        assert qualifiedName != null;
        FqName fqName = new FqName(qualifiedName);
        ResolverClassData classData = new ClassDescriptorFromJvmBytecode(
                containing, ClassKind.CLASS_OBJECT, classObjectPsiClass, fqName, javaDescriptorResolver)
                .getResolverBinaryClassData();

        ClassDescriptorFromJvmBytecode classObjectDescriptor = classData.getClassDescriptor();
        classObjectDescriptor.setSupertypes(
                getSupertypes(new PsiClassWrapper(classObjectPsiClass), classData, new ArrayList<TypeParameterDescriptor>(0)));
        setUpClassObjectDescriptor(containing, fqName, classData, getClassObjectName(containing.getName()));
        return classObjectDescriptor;
    }

    private void setUpClassObjectDescriptor(
            @NotNull ClassDescriptor containing,
            @NotNull FqNameBase fqName,
            @NotNull ResolverClassData data,
            @NotNull Name classObjectName
    ) {
        ClassDescriptorFromJvmBytecode classDescriptor = data.getClassDescriptor();
        classDescriptorCache.put(fqName, data);
        classDescriptor.setName(classObjectName);
        classDescriptor.setModality(Modality.FINAL);
        classDescriptor.setVisibility(containing.getVisibility());
        classDescriptor.setTypeParameterDescriptors(Collections.<TypeParameterDescriptor>emptyList());
        classDescriptor.createTypeConstructor();
        JavaClassMembersScope classMembersScope = new JavaClassMembersScope(semanticServices, data);
        WritableScopeImpl writableScope =
                new WritableScopeImpl(classMembersScope, classDescriptor, RedeclarationHandler.THROW_EXCEPTION, fqName.toString());
        writableScope.changeLockLevel(WritableScope.LockLevel.BOTH);
        classDescriptor.setScopeForMemberLookup(writableScope);
    }

    @NotNull
    private MutableClassDescriptorLite createClassObjectDescriptorForEnum(
            @NotNull ClassDescriptor containing,
            @NotNull PsiClass psiClass
    ) {
        MutableClassDescriptorLite classObjectDescriptor = createSyntheticClassObject(containing, psiClass);

        classObjectDescriptor.getBuilder()
                .addFunctionDescriptor(createEnumClassObjectValuesMethod(classObjectDescriptor, trace));
        classObjectDescriptor.getBuilder()
                .addFunctionDescriptor(createEnumClassObjectValueOfMethod(classObjectDescriptor, trace));

        return classObjectDescriptor;
    }

    @NotNull
    private MutableClassDescriptorLite createSyntheticClassObject(
            @NotNull ClassDescriptor containing,
            @NotNull PsiClass psiClass
    ) {
        String psiClassQualifiedName = psiClass.getQualifiedName();
        assert psiClassQualifiedName != null : "Reading java class with no qualified name";
        FqNameUnsafe fqName = new FqNameUnsafe(psiClassQualifiedName + "." + getClassObjectName(psiClass.getName()).getName());
        ClassDescriptorFromJvmBytecode classObjectDescriptor = new ClassDescriptorFromJvmBytecode(
                containing, ClassKind.CLASS_OBJECT, psiClass, null, javaDescriptorResolver);

        ResolverSyntheticClassObjectClassData
                data = new ResolverSyntheticClassObjectClassData(psiClass, null, classObjectDescriptor);
        setUpClassObjectDescriptor(containing, fqName, data, getClassObjectName(containing.getName().getName()));

        return classObjectDescriptor;
    }

    public Collection<JetType> getSupertypes(
            PsiClassWrapper psiClass,
            ResolverClassData classData,
            List<TypeParameterDescriptor> typeParameters
    ) {
        ClassDescriptor classDescriptor = classData.getClassDescriptor();

        final List<JetType> result = new ArrayList<JetType>();

        String context = "class " + psiClass.getQualifiedName();

        if (psiClass.getJetClass().signature().length() > 0) {
            final TypeVariableResolver typeVariableResolver =
                    TypeVariableResolvers.typeVariableResolverFromTypeParameters(typeParameters, classDescriptor, context);

            new JetSignatureReader(psiClass.getJetClass().signature()).accept(new JetSignatureExceptionsAdapter() {
                @Override
                public JetSignatureVisitor visitFormalTypeParameter(String name, TypeInfoVariance variance, boolean reified) {
                    // TODO: collect
                    return new JetSignatureAdapter();
                }

                @Override
                public JetSignatureVisitor visitSuperclass() {
                    return new JetTypeJetSignatureReader(semanticServices, JetStandardLibrary.getInstance(),
                                                         typeVariableResolver) {
                        @Override
                        protected void done(@NotNull JetType jetType) {
                            if (!jetType.equals(JetStandardClasses.getAnyType())) {
                                result.add(jetType);
                            }
                        }
                    };
                }

                @Override
                public JetSignatureVisitor visitInterface() {
                    return visitSuperclass();
                }
            });
        }
        else {
            TypeVariableResolver typeVariableResolverForSupertypes =
                    TypeVariableResolvers.typeVariableResolverFromTypeParameters(typeParameters, classDescriptor, context);
            transformSupertypeList(result, psiClass.getPsiClass().getExtendsListTypes(), typeVariableResolverForSupertypes);
            transformSupertypeList(result, psiClass.getPsiClass().getImplementsListTypes(), typeVariableResolverForSupertypes);
        }

        for (JetType supertype : result) {
            if (ErrorUtils.isErrorType(supertype)) {
                trace.record(BindingContext.INCOMPLETE_HIERARCHY, classDescriptor);
            }
        }

        if (result.isEmpty()) {
            if (classData.isKotlin()
                || DescriptorResolverUtils.OBJECT_FQ_NAME.equalsTo(psiClass.getQualifiedName())
                // TODO: annotations
                || classDescriptor.getKind() == ClassKind.ANNOTATION_CLASS) {
                result.add(JetStandardClasses.getAnyType());
            }
            else {
                ClassDescriptor object = resolveJavaLangObject();
                if (object != null) {
                    result.add(object.getDefaultType());
                }
                else {
                    result.add(JetStandardClasses.getAnyType());
                }
            }
        }
        return result;
    }

    private void transformSupertypeList(
            List<JetType> result,
            PsiClassType[] extendsListTypes,
            TypeVariableResolver typeVariableResolver
    ) {
        for (PsiClassType type : extendsListTypes) {
            PsiClass resolved = type.resolve();
            if (resolved != null) {
                final String qualifiedName = resolved.getQualifiedName();
                assert qualifiedName != null;
                if (JvmStdlibNames.JET_OBJECT.getFqName().equalsTo(qualifiedName)) {
                    continue;
                }
            }

            JetType transform = typeTransformer
                    .transformToType(type, JavaTypeTransformer.TypeUsage.SUPERTYPE, typeVariableResolver);
            if (ErrorUtils.isErrorType(transform)) {
                continue;
            }

            result.add(TypeUtils.makeNotNullable(transform));
        }
    }

    @NotNull
    private ClassOrNamespaceDescriptor resolveParentDescriptor(@NotNull PsiClass psiClass) {
        final String qualifiedName = psiClass.getQualifiedName();
        assert qualifiedName != null;
        FqName fqName = new FqName(qualifiedName);

        PsiClass containingClass = psiClass.getContainingClass();
        if (containingClass != null) {
            final String containingClassQualifiedName = containingClass.getQualifiedName();
            assert containingClassQualifiedName != null;
            FqName containerFqName = new FqName(containingClassQualifiedName);
            ClassDescriptor clazz = resolveClass(containerFqName, DescriptorSearchRule.INCLUDE_KOTLIN);
            if (clazz == null) {
                throw new IllegalStateException(
                        "PsiClass not found by name " + containerFqName + ", required to be container declaration of " + fqName);
            }
            if (DescriptorResolverUtils.isInnerEnum(psiClass, clazz) && DescriptorResolverUtils.isKotlinClass(psiClass)) {
                ClassDescriptor classObjectDescriptor = clazz.getClassObjectDescriptor();
                if (classObjectDescriptor == null) {
                    throw new IllegalStateException("Class object for a class with inner enum should've been created earlier: " + clazz);
                }
                return classObjectDescriptor;
            }
            return clazz;
        }

        NamespaceDescriptor ns = namespaceResolver.resolveNamespace(fqName.parent(), DescriptorSearchRule.INCLUDE_KOTLIN);
        if (ns == null) {
            throw new IllegalStateException("cannot resolve namespace " + fqName.parent() + ", required to be container for " + fqName);
        }
        return ns;
    }

    @Nullable
    private ClassDescriptor resolveJavaLangObject() {
        ClassDescriptor clazz = resolveClass(DescriptorResolverUtils.OBJECT_FQ_NAME, DescriptorSearchRule.IGNORE_IF_FOUND_IN_KOTLIN);
        if (clazz == null) {
            // TODO: warning
        }
        return clazz;
    }

    private static ClassKind getClassKind(@NotNull PsiClass psiClass, @NotNull JetClassAnnotation jetClassAnnotation) {
        if (psiClass.isInterface()) {
            return (psiClass.isAnnotationType() ? ClassKind.ANNOTATION_CLASS : ClassKind.TRAIT);
        }
        else {
            if (psiClass.isEnum()) {
                return ClassKind.ENUM_CLASS;
            }
            else {
                return jetClassAnnotation.kind() == JvmStdlibNames.FLAG_CLASS_KIND_OBJECT ? ClassKind.OBJECT : ClassKind.CLASS;
            }
        }
    }

    @Nullable
    private static PsiClass getInnerClassClassObject(@NotNull PsiClass outer) {
        for (PsiClass inner : outer.getInnerClasses()) {
            if (inner.getName().equals(JvmAbi.CLASS_OBJECT_CLASS_NAME)) {
                return inner;
            }
        }
        return null;
    }


    @Nullable
    public ClassDescriptor resolveClass(FqName name) {
        return resolveClass(name, DescriptorSearchRule.ERROR_IF_FOUND_IN_KOTLIN);
    }
}