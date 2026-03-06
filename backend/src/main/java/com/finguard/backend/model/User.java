package com.finguard.backend.model;

// Jakarta Persistence annotations for ORM (Object Relational Mapping)
import jakarta.persistence.*;

// Validation annotations used to validate user input
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// Lombok annotations to reduce boilerplate code
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// Spring Security interfaces for authentication and authorization
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

// Java utility imports
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;


/*
@Data
→ Lombok automatically generates:
   - getters
   - setters
   - toString()
   - equals()
   - hashCode()

@Builder
→ Allows object creation using the builder pattern
   Example:
   User user = User.builder()
                   .name("Viraj")
                   .email("test@mail.com")
                   .password("123")
                   .build();

@NoArgsConstructor
→ Generates a default constructor

@AllArgsConstructor
→ Generates a constructor with all fields
*/
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor

/*
@Entity
→ Marks this class as a JPA entity.
→ It will be mapped to a table in the database.
*/
@Entity

/*
@Table(name = "users")
→ Specifies the table name in the database.
→ If not provided, Spring would use "user".
*/
@Table(name = "users")

/*
UserDetails
→ Spring Security interface.
→ Required when implementing custom authentication logic.
→ Spring Security will use this class to load user information.
*/
public class User implements UserDetails {

    /*
    @Id
    → Marks this field as the Primary Key.

    @GeneratedValue(strategy = GenerationType.UUID)
    → Automatically generates a UUID for each new user.
    → Example: "3f9d4a9e-92f4-4b41-8c4f-45c3a23c7a92"
    */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;


    /*
    @NotBlank
    → Validation constraint.
    → Ensures the field is not null and not empty.
    */
    @NotBlank
    private String name;


    /*
    @Email
    → Validates the field contains a valid email format.

    @NotBlank
    → Email cannot be empty.

    @Column(unique = true)
    → Ensures each email in the database is unique.
    → Prevents duplicate user registrations.
    */
    @Email
    @NotBlank
    @Column(unique = true)
    private String email;


    /*
    @NotBlank
    → Password cannot be empty.

    Note:
    Passwords should always be stored as hashed values
    using BCrypt or another secure hashing algorithm.
    */
    @NotBlank
    private String password;


    /*
    createdAt
    → Stores the timestamp when the user was created.
    → Useful for auditing and tracking.
    */
    @Column(name = "created_at")
    private LocalDateTime createdAt;


    /*
    @PrePersist
    → JPA lifecycle callback.
    → This method runs automatically BEFORE the entity is saved
      for the first time in the database.

    Here it sets createdAt to the current time.
    */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }


    /*
    getAuthorities()
    → Returns the roles/permissions of the user.

    Example authorities:
       ROLE_ADMIN
       ROLE_USER

    Currently returning an empty list → meaning the user
    has no roles defined.
    */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }


    /*
    getUsername()
    → Spring Security uses this as the login identifier.

    Instead of username, we are using email as the login field.
    */
    @Override
    public String getUsername() {
        return email;
    }


    /*
    These 4 methods are required by Spring Security's UserDetails interface.
    They define the account status.
    */

    // Returns true if account is NOT expired
    @Override
    public boolean isAccountNonExpired()  { 
        return true; 
    }

    // Returns true if account is NOT locked
    @Override
    public boolean isAccountNonLocked()   { 
        return true; 
    }

    // Returns true if credentials (password) are NOT expired
    @Override
    public boolean isCredentialsNonExpired() { 
        return true; 
    }

    // Returns true if account is enabled
    @Override
    public boolean isEnabled() { 
        return true; 
    }
}