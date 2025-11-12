package org.qubership.nifi.processors;

import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.components.Validator;
import org.apache.nifi.expression.AttributeExpression;
import org.apache.nifi.processor.util.StandardValidators;

public class ListJsonDynamicPropertyValidator implements Validator {

    private final Validator internalValidator = StandardValidators.createRegexValidator(
            0,
            Integer.MAX_VALUE,
            true
    );

    @Override
    public ValidationResult validate(String subject, String input, ValidationContext context) {
        if (context.isExpressionLanguageSupported(subject) && context.isExpressionLanguagePresent(input)) {
            final AttributeExpression.ResultType resultType;
            try {
                resultType = context.newExpressionLanguageCompiler().getResultType(input);
            } catch (IllegalArgumentException e) {
                return new ValidationResult.Builder()
                        .subject(subject)
                        .input(input)
                        .valid(false)
                        .explanation("Invalid Expression Language: " + e.getMessage())
                        .build();
            }

            if (!AttributeExpression.ResultType.STRING.equals(resultType)) {
                return new ValidationResult.Builder()
                        .subject(subject)
                        .input(input)
                        .valid(false)
                        .explanation("Expected property to return type " + AttributeExpression.ResultType.STRING
                                + " but expression returns type " + resultType)
                        .build();
            }
            return new ValidationResult.Builder()
                    .subject(subject)
                    .input(input)
                    .valid(true)
                    .explanation("Property returns type " + AttributeExpression.ResultType.STRING)
                    .build();
        }

        return internalValidator.validate(subject, input, context);
    }

    public static Validator getInstance() {
        return new ListJsonDynamicPropertyValidator();
    }
}
