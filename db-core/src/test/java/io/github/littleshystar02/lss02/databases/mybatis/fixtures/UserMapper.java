package io.github.littleshystar02.lss02.databases.mybatis.fixtures;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** Test MyBatis mapper for the {@code users} table. */
public interface UserMapper {

    /**
     * Inserts a user.
     *
     * @param user the user to store
     */
    @Insert("INSERT INTO users (id, name) VALUES (#{id}, #{name})")
    void insert(User user);

    /**
     * Loads all users ordered by id.
     *
     * @return the users, empty if the table is empty
     */
    @Select("SELECT id, name FROM users ORDER BY id")
    List<User> findAll();
}
