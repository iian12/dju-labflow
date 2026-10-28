package com.dju.labflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 테스트 전용 H2와 JWT 설정으로 전체 애플리케이션 컨텍스트가 시작되는지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class BackendApplicationTests {

    @Test
    void contextLoads() {
    }

}
