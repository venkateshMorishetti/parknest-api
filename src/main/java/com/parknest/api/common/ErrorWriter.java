package com.parknest.api.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Writes the error envelope from filters (401, 403), where @RestControllerAdvice does not run. */
@Component
public class ErrorWriter {

    private final ObjectMapper json;

    public ErrorWriter(ObjectMapper json) {
        this.json = json;
    }

    public void write(HttpServletResponse response, int status, String message, String code, String text) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(),
                ApiResponse.fail(status, message, List.of(new ApiErrorItem(code, null, text)), Responses.meta(Map.of())));
    }
}
