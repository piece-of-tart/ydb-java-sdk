// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: kikimr/public/api/grpc/ydb_scripting_v1.proto

package tech.ydb.scripting.v1;

public final class YdbScriptingV1 {
  private YdbScriptingV1() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n-kikimr/public/api/grpc/ydb_scripting_v" +
      "1.proto\022\020Ydb.Scripting.V1\032,kikimr/public" +
      "/api/protos/ydb_scripting.proto2e\n\020Scrip" +
      "tingService\022Q\n\nExecuteYql\022 .Ydb.Scriptin" +
      "g.ExecuteYqlRequest\032!.Ydb.Scripting.Exec" +
      "uteYqlResponseB\035\n\033tech.ydb.scripti" +
      "ng.v1b\006proto3"
    };
    com.google.protobuf.Descriptors.FileDescriptor.InternalDescriptorAssigner assigner =
        new com.google.protobuf.Descriptors.FileDescriptor.    InternalDescriptorAssigner() {
          public com.google.protobuf.ExtensionRegistry assignDescriptors(
              com.google.protobuf.Descriptors.FileDescriptor root) {
            descriptor = root;
            return null;
          }
        };
    com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
          tech.ydb.scripting.ScriptingProtos.getDescriptor(),
        }, assigner);
    tech.ydb.scripting.ScriptingProtos.getDescriptor();
  }

  // @@protoc_insertion_point(outer_class_scope)
}