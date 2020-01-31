/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
 */

package com.palantir.conjure.java.services.dialogue;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.palantir.conjure.java.visitor.DefaultTypeVisitor;
import com.palantir.conjure.spec.ArgumentDefinition;
import com.palantir.conjure.spec.BodyParameterType;
import com.palantir.conjure.spec.EndpointDefinition;
import com.palantir.conjure.spec.ExternalReference;
import com.palantir.conjure.spec.HeaderParameterType;
import com.palantir.conjure.spec.ListType;
import com.palantir.conjure.spec.MapType;
import com.palantir.conjure.spec.OptionalType;
import com.palantir.conjure.spec.ParameterType;
import com.palantir.conjure.spec.PathParameterType;
import com.palantir.conjure.spec.PrimitiveType;
import com.palantir.conjure.spec.QueryParameterType;
import com.palantir.conjure.spec.ServiceDefinition;
import com.palantir.conjure.spec.SetType;
import com.palantir.conjure.spec.Type;
import com.palantir.conjure.visitor.ParameterTypeVisitor;
import com.palantir.dialogue.Channel;
import com.palantir.dialogue.ConjureRuntime;
import com.palantir.dialogue.Deserializer;
import com.palantir.dialogue.PlainSerDe;
import com.palantir.dialogue.Request;
import com.palantir.dialogue.Serializer;
import com.palantir.dialogue.TypeMarker;
import com.palantir.logsafe.exceptions.SafeIllegalArgumentException;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.lang.model.element.Modifier;

public final class AsyncGenerator {

    private final ParameterTypeMapper parameterTypes;
    private final ReturnTypeMapper returnTypes;

    public AsyncGenerator(ParameterTypeMapper parameterTypes, ReturnTypeMapper returnTypes) {
        this.parameterTypes = parameterTypes;
        this.returnTypes = returnTypes;
    }

    public MethodSpec generate(ClassName serviceClassName, ServiceDefinition def) {
        TypeSpec.Builder impl = TypeSpec.anonymousClassBuilder("").addSuperinterface(Names.asyncClassName(def));

        impl.addField(FieldSpec.builder(PlainSerDe.class, "plainSerDe")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .initializer("runtime.plainSerDe()")
                .build());

        def.getEndpoints().forEach(endpoint -> {
            endpoint.getArgs().stream()
                    .filter(arg -> arg.getParamType().accept(ParameterTypeVisitor.IS_BODY))
                    .findAny()
                    .ifPresent(body ->
                            impl.addField(serializer(endpoint.getEndpointName().get(), body.getType())));
            impl.addField(deserializer(endpoint.getEndpointName().get(), endpoint.getReturns()));
            impl.addMethod(asyncClientImpl(serviceClassName, endpoint));
        });

        MethodSpec asyncImpl = MethodSpec.methodBuilder("async")
                .addModifiers(Modifier.STATIC, Modifier.PUBLIC)
                .addJavadoc(
                        "Creates an asynchronous/non-blocking client for a $L service.",
                        def.getServiceName().getName())
                .returns(Names.asyncClassName(def))
                .addParameter(Channel.class, "channel")
                .addParameter(ConjureRuntime.class, "runtime")
                .addCode(CodeBlock.builder().add("return $L;", impl.build()).build())
                .build();
        return asyncImpl;
    }

    private FieldSpec serializer(String endpointName, Type type) {
        TypeName className = returnTypes.baseType(type).box();
        ParameterizedTypeName deserializerType = ParameterizedTypeName.get(ClassName.get(Serializer.class), className);
        return FieldSpec.builder(deserializerType, endpointName + "Serializer")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .initializer("runtime.bodySerDe().serializer(new $T<$T>() {})", TypeMarker.class, className)
                .build();
    }

    private FieldSpec deserializer(String endpointName, Optional<Type> type) {
        TypeName className = returnTypes.baseType(type).box();
        ParameterizedTypeName deserializerType =
                ParameterizedTypeName.get(ClassName.get(Deserializer.class), className);

        CodeBlock realDeserializer = CodeBlock.of(".deserializer(new $T<$T>() {})", TypeMarker.class, className);
        CodeBlock voidDeserializer = CodeBlock.of(".emptyBodyDeserializer()");
        CodeBlock initializer = CodeBlock.builder()
                .add("runtime.bodySerDe()")
                .add(type.isPresent() ? realDeserializer : voidDeserializer)
                .build();

        return FieldSpec.builder(deserializerType, endpointName + "Deserializer")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .initializer(initializer)
                .build();
    }

    private MethodSpec asyncClientImpl(ClassName serviceClassName, EndpointDefinition def) {
        List<ParameterSpec> params = parameterTypes.methodParams(def);
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(
                        def.getEndpointName().get())
                .addModifiers(Modifier.PUBLIC)
                .addParameters(params)
                .addAnnotation(Override.class);

        methodBuilder.returns(returnTypes.async(def.getReturns()));

        CodeBlock.Builder requestParams = CodeBlock.builder();
        def.getAuth().ifPresent(auth -> {
            Auth.verifyAuthTypeIsHeader(auth);
            requestParams.add(
                    "$L.putHeaderParams($S, plainSerDe.serializeBearerToken($L.getBearerToken()));",
                    "_request",
                    Auth.AUTH_HEADER_NAME,
                    Auth.AUTH_HEADER_PARAM_NAME);
        });

        def.getArgs().forEach(param ->
                addParameter(requestParams, def.getEndpointName().get(), param));

        CodeBlock request = CodeBlock.builder()
                .add("$T $L = $T.builder();", Request.Builder.class, "_request", Request.class)
                .add(requestParams.build())
                .build();
        CodeBlock execute = CodeBlock.builder()
                .add(
                        "channel.execute($T.$L, $L.build())",
                        serviceClassName,
                        def.getEndpointName().get(),
                        "_request")
                .build();
        CodeBlock transformed = CodeBlock.builder()
                .add(
                        "return $T.transform($L, $L::deserialize, $T.directExecutor());",
                        Futures.class,
                        execute,
                        def.getEndpointName().get() + "Deserializer",
                        MoreExecutors.class)
                .build();

        MethodSpec asyncClient = methodBuilder
                .addCode(request)
                .addCode(transformed)
                .build();
        return asyncClient;
    }

    private void addParameter(CodeBlock.Builder request, String endpointName, ArgumentDefinition param) {
        param.getParamType().accept(new ParameterType.Visitor<CodeBlock.Builder>() {
            @Override
            public CodeBlock.Builder visitBody(BodyParameterType value) {
                return request.add(
                        "$L.body($LSerializer.serialize($L));", "_request", endpointName, param.getArgName());
            }

            @Override
            public CodeBlock.Builder visitHeader(HeaderParameterType value) {
                return param.getType().accept(new DefaultTypeVisitor<CodeBlock.Builder>() {
                    @Override
                    public CodeBlock.Builder visitOptional(OptionalType optionalType) {
                        return request.add(
                                "$L.ifPresent(v -> $L.putHeaderParams($S, $T.toString(v)));",
                                param.getArgName(),
                                "_request",
                                value.getParamId(),
                                Objects.class);
                    }

                    @Override
                    public CodeBlock.Builder visitReference(com.palantir.conjure.spec.TypeName unused) {
                        return request.add(
                                "$L.putHeaderParams($S, $L.toString());",
                                "_request",
                                value.getParamId(),
                                param.getArgName());
                    }

                    @Override
                    public CodeBlock.Builder visitList(ListType unused) {
                        return visitCollection();
                    }

                    @Override
                    public CodeBlock.Builder visitSet(SetType unused) {
                        return visitCollection();
                    }

                    private CodeBlock.Builder visitCollection() {
                        return request.add(
                                "$L.putAllHeaderParams($S, plainSerDe.serialize$L($L));",
                                "_request",
                                value.getParamId(),
                                param.getType().accept(PlainSerializer.INSTANCE),
                                param.getArgName());
                    }

                    @Override
                    public CodeBlock.Builder visitDefault() {
                        return request.add(
                                "$L.putHeaderParams($S, plainSerDe.serialize$L($L));",
                                "_request",
                                value.getParamId(),
                                param.getType().accept(PlainSerializer.INSTANCE),
                                param.getArgName());
                    }
                });
            }

            @Override
            public CodeBlock.Builder visitPath(PathParameterType value) {
                return param.getType().accept(new DefaultTypeVisitor<CodeBlock.Builder>() {
                    @Override
                    public CodeBlock.Builder visitReference(com.palantir.conjure.spec.TypeName unused) {
                        return request.add(
                                "$L.putPathParams($S, $L.toString());",
                                "_request",
                                param.getArgName(),
                                param.getArgName());
                    }

                    @Override
                    public CodeBlock.Builder visitDefault() {
                        return request.add(
                                "$L.putPathParams($S, plainSerDe.serialize$L($L));",
                                "_request",
                                param.getArgName(),
                                param.getType().accept(PlainSerializer.INSTANCE),
                                param.getArgName());
                    }
                });
            }

            @Override
            public CodeBlock.Builder visitQuery(QueryParameterType value) {
                return param.getType().accept(new DefaultTypeVisitor<CodeBlock.Builder>() {
                    @Override
                    public CodeBlock.Builder visitOptional(OptionalType optionalType) {
                        return request.add(
                                "$L.ifPresent(v -> $L.putQueryParams($S, $T.toString(v)));",
                                param.getArgName(),
                                "_request",
                                value.getParamId(),
                                Objects.class);
                    }

                    @Override
                    public CodeBlock.Builder visitReference(com.palantir.conjure.spec.TypeName unused) {
                        return request.add(
                                "$L.putQueryParams($S, $L.toString());",
                                "_request",
                                value.getParamId(),
                                param.getArgName());
                    }

                    @Override
                    public CodeBlock.Builder visitList(ListType value) {
                        return visitCollection();
                    }

                    @Override
                    public CodeBlock.Builder visitSet(SetType value) {
                        return visitCollection();
                    }

                    private CodeBlock.Builder visitCollection() {
                        return request.add(
                                "$L.putAllQueryParams($S, plainSerDe.serialize$L($L));",
                                "_request",
                                value.getParamId(),
                                param.getType().accept(PlainSerializer.INSTANCE),
                                param.getArgName());
                    }

                    @Override
                    public CodeBlock.Builder visitDefault() {
                        return request.add(
                                "$L.putQueryParams($S, plainSerDe.serialize$L($L));",
                                "_request",
                                value.getParamId(),
                                param.getType().accept(PlainSerializer.INSTANCE),
                                param.getArgName());
                    }
                });
            }

            @Override
            public CodeBlock.Builder visitUnknown(String unknownType) {
                throw new UnsupportedOperationException("Unknown parameter type: " + unknownType);
            }
        });
    }

    private static class PlainSerializer extends DefaultTypeVisitor<String> {
        private static final PlainSerializer INSTANCE = new PlainSerializer();

        @Override
        public String visitPrimitive(PrimitiveType primitiveType) {
            return primitiveTypeName(primitiveType);
        }

        @Override
        public String visitOptional(OptionalType value) {
            throw new SafeIllegalArgumentException("Cannot serialize optional");
        }

        @Override
        public String visitList(ListType value) {
            return value.getItemType().accept(PlainSerializer.INSTANCE) + "List";
        }

        @Override
        public String visitSet(SetType value) {
            return value.getItemType().accept(PlainSerializer.INSTANCE) + "Set";
        }

        @Override
        public String visitMap(MapType value) {
            throw new SafeIllegalArgumentException("Cannot serialize map");
        }

        @Override
        public String visitReference(com.palantir.conjure.spec.TypeName value) {
            throw new SafeIllegalArgumentException("Cannot serialize reference");
        }

        @Override
        public String visitExternal(ExternalReference value) {
            throw new SafeIllegalArgumentException("Cannot serialize external");
        }

        @Override
        public String visitDefault() {
            throw new SafeIllegalArgumentException("Only primitive types and collections can be serialized");
        }
    }

    private static final ImmutableMap<PrimitiveType.Value, String> PRIMITIVE_TO_TYPE_NAME = new ImmutableMap.Builder<
                    PrimitiveType.Value, String>()
            .put(PrimitiveType.Value.BEARERTOKEN, "BearerToken")
            .put(PrimitiveType.Value.BOOLEAN, "Boolean")
            .put(PrimitiveType.Value.DATETIME, "DateTime")
            .put(PrimitiveType.Value.DOUBLE, "Double")
            .put(PrimitiveType.Value.INTEGER, "Integer")
            .put(PrimitiveType.Value.RID, "Rid")
            .put(PrimitiveType.Value.SAFELONG, "SafeLong")
            .put(PrimitiveType.Value.STRING, "String")
            .put(PrimitiveType.Value.UUID, "Uuid")
            .build();

    private static String primitiveTypeName(PrimitiveType in) {
        String typeName = PRIMITIVE_TO_TYPE_NAME.get(in.get());
        if (typeName == null) {
            throw new IllegalStateException("unrecognized primitive type: " + in);
        }
        return typeName;
    }
}