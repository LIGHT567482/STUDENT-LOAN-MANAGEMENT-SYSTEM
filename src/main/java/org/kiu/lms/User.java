package org.kiu.lms;

public record User(long id, String username, String displayName, Role role, boolean active) {
}
