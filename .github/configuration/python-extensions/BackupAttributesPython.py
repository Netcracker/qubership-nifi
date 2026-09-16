# Copyright 2020-2025 NetCracker Technology Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import re

from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import ExpressionLanguageScope, PropertyDescriptor, StandardValidators

# Attributes the Java BackupAttributes processor never copies.
SKIPPED_ATTRIBUTES = frozenset(["path", "uuid", "filename"])


class BackupAttributesPython(FlowFileTransform):
    """Test-only Python port of org.qubership.nifi.processors.BackupAttributes.

    Copies every FlowFile attribute to "<prefix>.<name>", where the prefix is the value of the attribute named by
    Prefix Attribute. The path, uuid, and filename attributes are skipped, and so is every attribute whose whole name
    matches Excluded Attributes. The autotests use it to check that the image loads and runs Python processors.

    A FlowFile without the prefix attribute goes to the failure relationship. The Java processor throws a
    NullPointerException in that case, so its session is rolled back instead. Excluded Attributes is matched with
    the Python re module, which does not support every construct of java.util.regex.
    """

    class Java:
        implements = ["org.apache.nifi.python.processor.FlowFileTransform"]

    class ProcessorDetails:
        version = "0.0.1-SNAPSHOT"
        description = "Backups all FlowFile attributes by adding prefix to their names. " \
                      "Test-only Python port of the BackupAttributes processor."
        tags = ["Attribute", "BackupAttributes", "test"]

    PREFIX_ATTR = PropertyDescriptor(
        name="prefix-attr",
        display_name="Prefix Attribute",
        description="FlowFile attribute to use as prefix for backup attributes",
        required=False,
        sensitive=False,
        expression_language_scope=ExpressionLanguageScope.ENVIRONMENT,
    )

    EXCLUDED_ATTRS = PropertyDescriptor(
        name="excluded-attrs-regex",
        display_name="Excluded Attributes",
        description="Regular expression defining attributes to exclude from backup",
        required=False,
        sensitive=False,
        validators=[StandardValidators.REGULAR_EXPRESSION_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.ENVIRONMENT,
    )

    def __init__(self, **kwargs):
        super().__init__()

    def getPropertyDescriptors(self):
        return [self.PREFIX_ATTR, self.EXCLUDED_ATTRS]

    def transform(self, context, flowfile):
        # The Python API evaluates Expression Language only against an attribute map, so both properties are read
        # here rather than in onScheduled. The re module caches the compiled pattern between calls.
        prefix_attr = context.getProperty(self.PREFIX_ATTR).evaluateAttributeExpressions(flowfile).getValue()
        excluded_attrs = context.getProperty(self.EXCLUDED_ATTRS).evaluateAttributeExpressions(flowfile).getValue()

        prefix = flowfile.getAttribute(prefix_attr)
        if prefix is None:
            raise ValueError(f"FlowFile has no attribute '{prefix_attr}' to use as the backup prefix")

        backup_attributes = {}
        for key, value in flowfile.getAttributes().items():
            if key in SKIPPED_ATTRIBUTES:
                continue
            if excluded_attrs and re.fullmatch(excluded_attrs, key):
                continue
            backup_attributes[f"{prefix}.{key}"] = value

        return FlowFileTransformResult(relationship="success", attributes=backup_attributes)
