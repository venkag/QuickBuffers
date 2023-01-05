/*-
 * #%L
 * quickbuf-generator
 * %%
 * Copyright (C) 2019 HEBI Robotics
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

package us.hebi.quickbuf.generator;

import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import us.hebi.quickbuf.generator.RequestInfo.FileInfo;
import us.hebi.quickbuf.parser.ParserUtil;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Protoc plugin that gets called by the protoc executable. The communication happens
 * via protobuf messages on System.in / System.out
 *
 * @author Florian Enner
 * @since 05 Aug 2019
 */
public class CompilerPlugin {

    /**
     * The protoc-gen-plugin communicates via proto messages on System.in and System.out
     *
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
        if (args.length > 0) {
            System.out.println("This protobuf plugin should be called by protoc. Example:\n\n" +
                    "    1) protoc --plugin=protoc-gen-quickbuf=${executable} --quickbuf_out=store_unknown_fields=true:. type.proto\n" +
                    "    2) protoc --quickbuf_out=store_unknown_fields=true:. type.proto\n\n" +
                    "Note that if you are calling this plugin from the PATH (2), the executable\n" +
                    "file or wrapper script needs to be called \"protoc-gen-quickbuf\".");
            return;
        }
        handleRequest(System.in).writeTo(System.out);
    }

    static CodeGeneratorResponse handleRequest(InputStream input) throws IOException {
        try {
            return handleRequest(CodeGeneratorRequest.parseFrom(input));
        } catch (GeneratorException ge) {
            return ParserUtil.asError(ge.getMessage());
        } catch (Exception ex) {
            return ParserUtil.asErrorWithStackTrace(ex);
        }
    }

    static CodeGeneratorResponse handleRequest(CodeGeneratorRequest requestProto) {
        CodeGeneratorResponse.Builder response = CodeGeneratorResponse.newBuilder();
        RequestInfo request = RequestInfo.withTypeRegistry(requestProto);

        for (FileInfo file : request.getFiles()) {

            // Generate type specifications
            List<TypeSpec> topLevelTypes = new ArrayList<>();
            TypeSpec.Builder outerClassSpec = TypeSpec.classBuilder(file.getOuterClassName())
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL);
            Consumer<TypeSpec> list = file.isGenerateMultipleFiles() ? topLevelTypes::add : outerClassSpec::addType;

            for (RequestInfo.EnumInfo type : file.getEnumTypes()) {
                list.accept(new EnumGenerator(type).generate());
            }

            for (RequestInfo.MessageInfo type : file.getMessageTypes()) {
                list.accept(new MessageGenerator(type).generate());
            }

            // Omitt completely empty outer classes
            if (!file.isGenerateMultipleFiles()) {
                topLevelTypes.add(outerClassSpec.build());
            }

            // Generate Java files
            for (TypeSpec typeSpec : topLevelTypes) {

                JavaFile javaFile = JavaFile.builder(file.getJavaPackage(), typeSpec)
                        .addFileComment("Code generated by protocol buffer compiler. Do not edit!")
                        .indent(request.getIndentString())
                        .skipJavaLangImports(true)
                        .build();

                StringBuilder content = new StringBuilder(1000);
                try {
                    javaFile.writeTo(content);
                } catch (IOException e) {
                    throw new AssertionError("Could not write to StringBuilder?");
                }

                response.addFile(CodeGeneratorResponse.File.newBuilder()
                        .setName(file.getOutputDirectory() + typeSpec.name + ".java")
                        .setContent(content.toString())
                        .build());
            }

        }

        return response.build();

    }

}
