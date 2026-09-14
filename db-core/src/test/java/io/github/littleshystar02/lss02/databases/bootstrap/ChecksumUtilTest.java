package io.github.littleshystar02.lss02.databases.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ChecksumUtilTest {

    @Test
    void sameContentProducesTheSameChecksum() {
        assertEquals(ChecksumUtil.sha256("create table t (id int);"), ChecksumUtil.sha256("create table t (id int);"));
    }

    @Test
    void differentContentProducesDifferentChecksums() {
        assertNotEquals(ChecksumUtil.sha256("create table t (id int);"), ChecksumUtil.sha256("create table t (id bigint);"));
    }
}
