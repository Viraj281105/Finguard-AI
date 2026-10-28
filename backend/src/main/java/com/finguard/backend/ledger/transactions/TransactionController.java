package com.finguard.backend.ledger.transactions;

import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.identity.domain.User;
import com.finguard.backend.ledger.transactions.Transaction.TransactionType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping
    public ResponseEntity<TransactionResponse> create(@Valid @RequestBody TransactionRequest request,
                                                      @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponse.from(transactionService.create(request, user)));
    }

    @GetMapping
    public ResponseEntity<List<TransactionResponse>> getAll(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false, defaultValue = "false") boolean unpaged,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean needsReview
    ) {
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.badRequest("'from' date cannot be after 'to' date");
        }
        if (page != null && page < 0) {
            throw ApiException.badRequest("Page index must not be less than zero");
        }
        if (size != null && (size <= 0 || size > 100)) {
            throw ApiException.badRequest("Page size must be between 1 and 100");
        }

        Pageable pageable;
        Sort sort = Sort.by(Sort.Order.desc("date"), Sort.Order.desc("createdAt"));
        if (unpaged) {
            pageable = Pageable.unpaged(sort);
        } else {
            int pageIndex = (page != null) ? page : 0;
            int pageSize = (size != null) ? size : 20;
            pageable = PageRequest.of(pageIndex, pageSize, sort);
        }

        Page<Transaction> result = transactionService.list(user, from, to, type, category, needsReview, pageable);

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Total-Count", String.valueOf(result.getTotalElements()));
        if (pageable.isPaged()) {
            headers.add("X-Page", String.valueOf(result.getNumber()));
            headers.add("X-Page-Size", String.valueOf(result.getSize()));
            headers.add("X-Total-Pages", String.valueOf(result.getTotalPages()));
        } else {
            headers.add("X-Page", "0");
            headers.add("X-Page-Size", String.valueOf(result.getTotalElements()));
            headers.add("X-Total-Pages", "1");
        }

        List<TransactionResponse> body = result.getContent().stream()
                .map(TransactionResponse::from)
                .toList();

        return ResponseEntity.ok().headers(headers).body(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponse> getById(@PathVariable String id,
                                                       @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(TransactionResponse.from(transactionService.getById(id, user)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TransactionResponse> update(@PathVariable String id,
                                                      @Valid @RequestBody TransactionRequest request,
                                                      @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(TransactionResponse.from(transactionService.update(id, request, user)));
    }

    /** Records the user's own category. It is authoritative: automatic classification never replaces it. */
    @PatchMapping("/{id}/category")
    public ResponseEntity<TransactionResponse> updateCategory(@PathVariable String id,
                                                              @Valid @RequestBody CategoryUpdateRequest request,
                                                              @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(TransactionResponse.from(
                transactionService.setUserCategory(id, request.category(), user)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @AuthenticationPrincipal User user) {
        transactionService.delete(id, user);
        return ResponseEntity.noContent().build();
    }
}
