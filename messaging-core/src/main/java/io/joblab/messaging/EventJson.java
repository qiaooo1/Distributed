package io.joblab.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class EventJson {
    private final ObjectMapper mapper;

    public EventJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(JobDispatch event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize job dispatch", ex);
        }
    }

    public JobDispatch read(String body) {
        try {
            return mapper.readValue(body, JobDispatch.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid job dispatch", ex);
        }
    }
}
