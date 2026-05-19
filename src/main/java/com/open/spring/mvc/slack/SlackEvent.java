package com.open.spring.mvc.slack;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SlackEvent {
    // POJO
    private String challenge;
    private Event event;

    // Getters and Setters
    public String getChallenge() {
        return challenge;
    }

    public void setChallenge(String challenge) {
        this.challenge = challenge;
    }

    public Event getEvent() {
        return event;
    }

    public void setEvent(Event event) {
        this.event = event;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Event {
        private String type;
        private String user;
        private String text;
        private String channel;
        private String username;
        private String subtype;

        @JsonProperty("ts")
        private String ts;

        @JsonProperty("thread_ts")
        private String threadTs;

        @JsonProperty("reply_count")
        private Integer replyCount;

        @JsonProperty("parent_user_id")
        private String parentUserId;

        @JsonProperty("bot_id")
        private String botId;

        @JsonProperty("channel_name")
        private String channelName;

        @JsonProperty("bot_profile")
        private Map<String, Object> botProfile;

        // Getters and Setters
        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public String getChannel() {
            return channel;
        }

        public void setChannel(String channel) {
            this.channel = channel;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getSubtype() {
            return subtype;
        }

        public void setSubtype(String subtype) {
            this.subtype = subtype;
        }

        public String getTs() {
            return ts;
        }

        public void setTs(String ts) {
            this.ts = ts;
        }

        public String getThreadTs() {
            return threadTs;
        }

        public void setThreadTs(String threadTs) {
            this.threadTs = threadTs;
        }

        public Integer getReplyCount() {
            return replyCount;
        }

        public void setReplyCount(Integer replyCount) {
            this.replyCount = replyCount;
        }

        public String getParentUserId() {
            return parentUserId;
        }

        public void setParentUserId(String parentUserId) {
            this.parentUserId = parentUserId;
        }

        public String getBotId() {
            return botId;
        }

        public void setBotId(String botId) {
            this.botId = botId;
        }

        public String getChannelName() {
            return channelName;
        }

        public void setChannelName(String channelName) {
            this.channelName = channelName;
        }

        public Map<String, Object> getBotProfile() {
            return botProfile;
        }

        public void setBotProfile(Map<String, Object> botProfile) {
            this.botProfile = botProfile;
        }
    }
}
