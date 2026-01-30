package com.open.spring.mvc.generic;

import com.open.spring.mvc.identity.User;
import com.open.spring.mvc.identity.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/collections")
public class GenericCollectionController {

    @Autowired
    private UserCollectionItemRepository collectionRepository;

    @Autowired
    private UserRepository userRepository;

    @PostMapping("/{category}")
    public ResponseEntity<UserCollectionItem> addItem(
            @PathVariable String category,
            @RequestBody UserCollectionItem item,
            @AuthenticationPrincipal UserDetails userDetails) {

        if (userDetails != null) {
            String email = userDetails.getUsername();
            Optional<User> owner = userRepository.findByEmail(email);

            if (owner.isPresent()) {
                item.setOwner(owner.get());
            }
        }

        item.setCategory(category);
        UserCollectionItem saved = collectionRepository.save(item);
        return new ResponseEntity<>(saved, HttpStatus.CREATED);
    }

    @GetMapping("/{category}")
    public ResponseEntity<List<UserCollectionItem>> listItems(@PathVariable String category) {
        return new ResponseEntity<>(collectionRepository.findByCategory(category), HttpStatus.OK);
    }

    @GetMapping("/{category}/{ownerId}")
    public ResponseEntity<List<UserCollectionItem>> listItemsByOwner(
            @PathVariable String category,
            @PathVariable Long ownerId) {
        return new ResponseEntity<>(collectionRepository.findByOwnerIdAndCategory(ownerId, category), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteItem(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }

        Optional<UserCollectionItem> item = collectionRepository.findById(id);
        if (item.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        String email = userDetails.getUsername();
        if (!item.get().getOwner().getEmail().equals(email)) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }

        collectionRepository.deleteById(id);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
}
