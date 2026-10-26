package io.github.littleshystar02.lss02.databases.mybatis.fixtures;

import lombok.Data;

/** Test entity mapped by {@link UserMapper}. */
@Data
public class User {

    /** Primary key of the user. */
    private int id;
    /** Display name of the user. */
    private String name;
}
