package com.dju.labflow.sdk.presentation;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** 메트릭 값만 JSON 숫자로 제한한다. 다른 API의 Jackson 변환 정책은 변경하지 않는다. */
public final class MetricValueDeserializer extends ValueDeserializer<Double> {
    @Override
    public Double deserialize(JsonParser parser, DeserializationContext context)
            throws JacksonException {
        if (parser.currentToken() == null || !parser.currentToken().isNumeric()) {
            return context.reportInputMismatch(Double.class, "Metric values must be JSON numbers");
        }
        return parser.getDoubleValue();
    }
}
