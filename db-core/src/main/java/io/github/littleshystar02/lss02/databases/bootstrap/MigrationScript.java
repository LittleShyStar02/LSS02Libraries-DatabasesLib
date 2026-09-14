package io.github.littleshystar02.lss02.databases.bootstrap;

import lombok.Value;

@Value
public class MigrationScript {

    int version;
    String description;
    String scriptName;
    String content;
    String checksum;
}
