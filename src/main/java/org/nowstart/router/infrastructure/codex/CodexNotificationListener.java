package org.nowstart.router.infrastructure.codex;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface CodexNotificationListener {

    void onNotification(String method, JsonNode params);
}
