package com.nighthawk.spring_portfolio.mvc.forum;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/forum")
public class ForumAPIController {

    @Autowired
    private ForumRepository forumRepository;

    // Endpoint for posting new forum entries (dynamic input)
    @PostMapping("/issue/post")
    public ResponseEntity<String> createPost(@RequestBody RequestBodyData requestBodyData) {
        System.out.println("hello");
        if (requestBodyData.getTitle() == null || requestBodyData.getTitle().isEmpty() ||
            requestBodyData.getContext() == null || requestBodyData.getContext().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Error: Title or Problem is required.");
        }
        System.out.println("hello1");


        if (requestBodyData.getAuthor() == null || requestBodyData.getAuthor().isEmpty()) {
            String[] authorEnding = {"Whale", "Pig", "Badger", "Warthog", "Fish", "Cow", "Chicken", "Rabbit", "Wolf", "Bear"};
            requestBodyData.setAuthor("Anonymous" + authorEnding[(int) (Math.random() * 10)]);
        }

        String title = requestBodyData.getTitle();
        String context = requestBodyData.getContext();
        System.out.println(context);
        String author = requestBodyData.getAuthor();
        int views = requestBodyData.getViews();
        String date = java.time.LocalDate.now().toString();

        // Check if title already exists
        if (forumRepository.findByTitle(title) != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Error: Title already exists.");
        }

        Forum forumTable = new Forum(author,title, context, date, views);
        forumRepository.save(forumTable); // Save to repository

        return ResponseEntity.status(HttpStatus.CREATED).body("Successfully added to database");
    }

    // Endpoint for viewing a post by title
    @GetMapping("/title/{title}")
    public ResponseEntity<Forum> getPostByTitle(@PathVariable String title) {
        Forum forumPost = forumRepository.findByTitle(title);
        return forumPost != null ? ResponseEntity.ok(forumPost) : ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
    }

    // Endpoint for viewing a post by ID (dynamic route for specific post)
    @GetMapping("/{id}")
    public ResponseEntity<Forum> getPostById(@PathVariable Long id) {
        Optional<Forum> post = forumRepository.findById(id);
        return post.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(null));
    }

    // Endpoint for increasing the view count dynamically based on title
    @GetMapping("/increaseView/{title}")
    public ResponseEntity<String> increaseViewCount(@PathVariable String title) {
        Forum forumPost = forumRepository.findByTitle(title);
        if (forumPost == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Error: Forum post with the given title not found.");
        }

        forumPost.setViews(forumPost.getViews() + 1);
        forumRepository.save(forumPost);

        return ResponseEntity.ok("View count increased successfully!");
    }

    // Endpoint to serve the issue detail page (dynamically generate the content)
    @GetMapping("/studentIssue/{id}")
    public ResponseEntity<String> getIssuePage(@PathVariable Long id) {
        // Fetch the issue by ID
        Optional<Forum> forumPostOptional = forumRepository.findById(id);
        if (!forumPostOptional.isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Error: Issue not found.");
        }

        Forum forumPost = forumPostOptional.get();
        
        // Construct the markdown content for the issue page
        String issueMarkdown = generateIssuePageMarkdown(forumPost);

        return ResponseEntity.ok(issueMarkdown);
    }

    // Method to generate the markdown content for the issue detail page
    private String generateIssuePageMarkdown(Forum forumPost) {
        StringBuilder markdownContent = new StringBuilder();

        // Add title, author, and date
        markdownContent.append("# ").append(forumPost.getTitle()).append("\n\n");
        markdownContent.append("## Author: ").append(forumPost.getAuthor()).append("\n");
        markdownContent.append("## Date: ").append(forumPost.getDate()).append("\n\n");

        // Add issue context
        markdownContent.append("### Description:\n");
        markdownContent.append(forumPost.getContext()).append("\n");

        return markdownContent.toString();
    }

    // Endpoint to retrieve all posts (you can add pagination or filtering here if needed)
    @GetMapping("/get")
    public ResponseEntity<List<Forum>> getAllPosts() {
        List<Forum> posts = forumRepository.findAll();
        return ResponseEntity.ok(posts);
    }

    // Inner class for handling request body data
    public static class RequestBodyData {
        private String title;
        private String context;
        private String author;
        private int views;
        private String date;

        // Getters and Setters
        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getContext() {
            return context;
        }

        public void setContext(String context) {
            this.context = context;
        }

        public String getAuthor() {
            return author;
        }

        public void setAuthor(String author) {
            this.author = author;
        }

        public int getViews() {
            return views;
        }

        public void setViews(int views) {
            this.views = views;
        }

        public String getDate() {
            return date;
        }

        public void setDate(String date) {
            this.date = date;
        }

        

       
    }
}
