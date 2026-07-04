package com.interview.agent.graph;

/**
 * 用户主动终止面试异常
 *
 * @author 陈龙强
 */
public class UserQuitException extends Exception {
    public UserQuitException() {
        super("用户主动终止面试");
    }
}
