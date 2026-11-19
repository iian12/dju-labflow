package com.dju.labflow.run.run.application;

import com.dju.labflow.run.run.domain.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** 버전/Run/기록 종류에 묶인 URL-safe 커서. 접근 권한은 커서와 별도로 검사한다. */
@Component
public class RecordCursorCodec {
    public String encode(RunId runId, RecordKind kind, RecordPosition position) {
        String value =
                "1|"
                        + runId.value()
                        + "|"
                        + kind.path()
                        + "|"
                        + position.recordedAt()
                        + "|"
                        + position.id();
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public RecordPosition decode(String cursor, RunId runId, RecordKind kind) {
        if (cursor == null || cursor.isBlank() || cursor.length() > 512)
            throw new IllegalArgumentException("Invalid record cursor");
        try {
            String[] fields =
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                            .split("\\|", -1);
            if (fields.length != 5
                    || !fields[0].equals("1")
                    || !fields[1].equals(runId.value().toString())
                    || !fields[2].equals(kind.path()))
                throw new IllegalArgumentException("Cursor does not match this record stream");
            return new RecordPosition(Instant.parse(fields[3]), Long.parseLong(fields[4]));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid record cursor");
        }
    }
}
