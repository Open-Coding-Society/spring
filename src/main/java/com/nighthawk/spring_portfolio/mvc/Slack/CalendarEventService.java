package com.nighthawk.spring_portfolio.mvc.Slack;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CalendarEventService {

    @Autowired
    private CalendarEventRepository calendarEventRepository;

    @Autowired
    private SlackService slackService;

    private final String CSA_WEBHOOK_URL = "https://hooks.slack.com/services/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"; 
    private final String CSP_WEBHOOK_URL = "https://hooks.slack.com/services/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";
    private final String CSSE_WEBHOOK_URL = "https://hooks.slack.com/services/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"; 
    private String SLACK_WEBHOOK_URL = CSA_WEBHOOK_URL;

    // Save a new event
    public CalendarEvent saveEvent(CalendarEvent event) {
        CalendarEvent savedEvent = calendarEventRepository.save(event);
        //slackService.sendMessage("New Event Added:\n" +
        //        "Title: " + savedEvent.getTitle() + "\n" +
        //        "Description: " + savedEvent.getDescription() + "\n" +
        //        "Date: " + savedEvent.getDate() + "\n" +
        //        "Type: " + savedEvent.getType() + "\n" +
        //        "Period: " + savedEvent.getPeriod(), SLACK_WEBHOOK_URL);
        return savedEvent;
    }

    // Create a calendar event
    public void createCalendarEvent(String title, LocalDate eventDate, String description, String type, String period) {
        CalendarEvent event = new CalendarEvent();
        event.setTitle(title);
        event.setDate(eventDate);
        event.setDescription(description);
        event.setType(type);
        event.setPeriod(period);
        calendarEventRepository.save(event);
    }

    // Get events by a specific date
    public List<CalendarEvent> getEventsByDate(LocalDate date) {
        return calendarEventRepository.findByDate(date);
    }

    // Update event by id
    public boolean updateEventById(int id, String newTitle, String description, LocalDate date) {
        CalendarEvent event = getEventById(id);
        if (event != null) {
            String oldDetails = "Old Event Details:\n" +
                    "Title: " + event.getTitle() + "\n" +
                    "Description: " + event.getDescription() + "\n" +
                    "Date: " + event.getDate() + "\n" +
                    "Type: " + event.getType() + "\n" +
                    "Period: " + event.getPeriod();

            event.setTitle(newTitle);
            event.setDescription(description);
            event.setDate(date);
            calendarEventRepository.save(event);

            String newDetails = "Updated Event Details:\n" +
                    "Title: " + event.getTitle() + "\n" +
                    "Description: " + event.getDescription() + "\n" +
                    "Date: " + event.getDate() + "\n" +
                    "Type: " + event.getType() + "\n" +
                    "Period: " + event.getPeriod();

            //slackService.sendMessage("Event Updated:\n" + oldDetails + "\n\n" + newDetails, SLACK_WEBHOOK_URL);
            return true;
        }
        return false;
    }

    // Delete event by id
    public boolean deleteEventById(int id) {
        CalendarEvent event = getEventById(id);
        if (event != null) {
            calendarEventRepository.delete(event);
            //slackService.sendMessage("Event Deleted:\n" +
            //        "Title: " + event.getTitle() + "\n" +
            //        "Description: " + event.getDescription() + "\n" +
            //        "Date: " + event.getDate() + "\n" +
            //        "Type: " + event.getType() + "\n" +
            //        "Period: " + event.getPeriod(), SLACK_WEBHOOK_URL);
            return true;
        }
        return false;
    }

    // Delete event by title
    public boolean deleteEventByTitle(String title) {
        List<CalendarEvent> allEvents = calendarEventRepository.findAll(); 
        List<CalendarEvent> eventsToDelete = allEvents.stream()
                .filter(event -> event.getTitle().equals(title))
                .toList();

        if (!eventsToDelete.isEmpty()) {
            eventsToDelete.forEach(calendarEventRepository::delete);
            return true;
        }
        return false;
    }

    // Retrieve all events
    public List<CalendarEvent> getAllEvents() {
        return calendarEventRepository.findAll();
    }

    public List<CalendarEvent> getEventsWithinDateRange(LocalDate startDate, LocalDate endDate) {
        return calendarEventRepository.findByDateBetween(startDate, endDate);
    }

    // Get event by id
    public CalendarEvent getEventById(int id) {
        return calendarEventRepository.findById((long) id).orElse(null);
    }

    // Parse Slack message and create events
    public void parseSlackMessage(Map<String, String> jsonMap, LocalDate weekStartDate) {
        List<CalendarEvent> events = extractEventsFromText(jsonMap, weekStartDate);
        for (CalendarEvent event : events) {
            saveEvent(event);
        }
    }

    private final String CSP_CHANNEL_ID = "CUS8E3M6Z";
    private final String CSA_CHANNEL_ID = "CRRJL1F1D";
    private final String CSSE_CHANNEL_ID = "C05MNRWC2A1";

    private List<CalendarEvent> extractEventsFromText(Map<String, String> jsonMap, LocalDate weekStartDate) {
        String text = jsonMap.get("text");
        // Use SlackService to determine the correct week start date from the message
        LocalDate parsedWeekStartDate = null;
        try {
            java.lang.reflect.Method method = SlackService.class.getDeclaredMethod("getWeekStartDateFromMessage", String.class);
            method.setAccessible(true);
            parsedWeekStartDate = (LocalDate) method.invoke(slackService, text);
        } catch (Exception e) {
            // Handle exception or log as needed
        }
        if (parsedWeekStartDate != null) {
            weekStartDate = parsedWeekStartDate;
        }
        List<CalendarEvent> events = new ArrayList<>();
        Pattern dayPattern = Pattern.compile("\\[(Mon|Tue|Wed|Thu|Fri|Sat|Sun)(?: - (Mon|Tue|Wed|Thu|Fri|Sat|Sun))?\\]:\\s*(\\*\\*|\\*)?\\s*(.+)");
        Pattern descriptionPattern = Pattern.compile("(\\*\\*|\\*)?\\s*\\u2022\\s*(.+)");
        String[] lines = text.split("\\n");
        CalendarEvent lastEvent = null;

        for (String line : lines) {
            Matcher dayMatcher = dayPattern.matcher(line);

            if (dayMatcher.find()) {
                String startDay = dayMatcher.group(1);
                String endDay = dayMatcher.group(2) != null ? dayMatcher.group(2) : startDay;
                String asterisks = dayMatcher.group(3);
                String currentTitle = dayMatcher.group(4).trim();
                String period = "0";
                switch(jsonMap.get("channel")) {
                    case CSP_CHANNEL_ID:
                        period = "CSP";
                        SLACK_WEBHOOK_URL = CSP_WEBHOOK_URL;
                        break;
                    case CSA_CHANNEL_ID:
                        period = "CSA";
                        SLACK_WEBHOOK_URL = CSA_WEBHOOK_URL;
                        break;
                    case CSSE_CHANNEL_ID:
                        period = "CSSE";
                        SLACK_WEBHOOK_URL = CSSE_WEBHOOK_URL;
                        break;
                }

                String type = "daily plan";
                if ("*".equals(asterisks)) {
                    type = "check-in";
                } else if ("**".equals(asterisks)) {
                    type = "grade";
                }

                for (LocalDate date : getDatesInRange(startDay, endDay, weekStartDate)) {
                    lastEvent = new CalendarEvent(date, currentTitle, "", type, period);
                    events.add(lastEvent);
                }
            } else {
                Matcher descMatcher = descriptionPattern.matcher(line);
                if (descMatcher.find() && lastEvent != null) {
                    String description = descMatcher.group(2).trim();
                    String asterisks = descMatcher.group(1);

                    String type = lastEvent.getType();
                    if ("*".equals(asterisks)) {
                        type = "check-in";
                    } else if ("**".equals(asterisks)) {
                        type = "grade";
                    }

                    lastEvent.setDescription(lastEvent.getDescription() +
                            (lastEvent.getDescription().isEmpty() ? "" : ", ") +
                            description);
                    lastEvent.setType(type);
                }
            }
        }
        return events;
    }

    private List<LocalDate> getDatesInRange(String startDay, String endDay, LocalDate weekStartDate) {
        List<String> days = List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun");
        int startIndex = days.indexOf(startDay);
        int endIndex = days.indexOf(endDay);

        List<LocalDate> dateRange = new ArrayList<>();
        if (startIndex != -1 && endIndex != -1) {
            for (int i = startIndex; i <= endIndex; i++) {
                dateRange.add(weekStartDate.plusDays(i - weekStartDate.getDayOfWeek().getValue() + 1));
            }
        }
        return dateRange;
    }
}
