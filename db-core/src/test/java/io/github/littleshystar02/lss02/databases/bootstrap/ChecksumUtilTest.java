package io.github.littleshystar02.lss02.databases.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Tests for {@link ChecksumUtil}. */
class ChecksumUtilTest {

    /** The checksum is deterministic for equal content. */
    @Test
    void sameContentProducesTheSameChecksum() {
        assertEquals(ChecksumUtil.sha256("create table t (id int);"), ChecksumUtil.sha256("create table t (id int);"));
    }

    /** Different content yields different checksums. */
    @Test
    void differentContentProducesDifferentChecksums() {
        assertNotEquals(ChecksumUtil.sha256("create table t (id int);"), ChecksumUtil.sha256("create table t (id bigint);"));
    }
}
