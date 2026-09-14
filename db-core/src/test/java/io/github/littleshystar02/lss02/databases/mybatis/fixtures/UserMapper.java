package io.github.littleshystar02.lss02.databases.mybatis.fixtures;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface UserMapper {

    @Insert("INSERT INTO users (id, name) VALUES (#{id}, #{name})")
    void insert(User user);

    @Select("SELECT id, name FROM users ORDER BY id")
    List<User> findAll();
}
