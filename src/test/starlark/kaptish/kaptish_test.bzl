# Copyright 2026 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

load("@bazel_skylib//lib:unittest.bzl", "analysistest", "asserts")
load("//kotlin/internal:defs.bzl", "KtJvmInfo")

def _kaptish_generated_sources_test_impl(ctx):
    """Verify Kaptish-generated sources are included in KtJvmInfo."""
    env = analysistest.begin(ctx)
    kotlin_info = analysistest.target_under_test(env)[KtJvmInfo]
    kaptish_source_jar = "kaptish_test_lib-java-gensrc.jar"

    output_generated_source_jars = [
        jar.basename
        for output in kotlin_info.outputs.jars
        for jar in output.generated_src_jars
    ]
    asserts.true(
        env,
        kaptish_source_jar in output_generated_source_jars,
        "KtJvmInfo outputs should include Kaptish-generated sources",
    )

    additional_generated_source_jars = [
        jar.basename
        for jar in kotlin_info.additional_generated_source_jars
    ]
    asserts.true(
        env,
        kaptish_source_jar in additional_generated_source_jars,
        "KtJvmInfo should include Kaptish-generated sources",
    )

    return analysistest.end(env)

kaptish_generated_sources_test = analysistest.make(
    _kaptish_generated_sources_test_impl,
    config_settings = {
        "//command_line_option:extra_toolchains": [
            "//src/test/starlark/kaptish:kaptish_toolchain",
        ],
    },
)
