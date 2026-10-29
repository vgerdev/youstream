package com.youstream.common;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PublicIdsTest {

    @Test
    void generatesValidDistinctIds() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String id = PublicIds.next();
            assertThat(PublicIds.isValid(id)).as(id).isTrue();
            ids.add(id);
        }
        assertThat(ids).hasSize(10_000);
    }

    @Test
    void rejectsMalformedIds() {
        assertThat(PublicIds.isValid(null)).isFalse();
        assertThat(PublicIds.isValid("short")).isFalse();
        assertThat(PublicIds.isValid("abc-def_ghi")).isFalse();
        assertThat(PublicIds.isValid("../../etc/x")).isFalse();
    }
}
