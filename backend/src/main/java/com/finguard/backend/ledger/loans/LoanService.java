package com.finguard.backend.ledger.loans;

import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.identity.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LoanService {

    private final LoanRepository loanRepository;

    public Loan create(LoanRequest request, User user) {
        Loan l = new Loan();
        l.setUser(user);
        apply(l, request);
        return loanRepository.save(l);
    }

    @Transactional(readOnly = true)
    public List<Loan> getAll(User user) {
        return loanRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    @Transactional(readOnly = true)
    public Loan getById(String id, User user) {
        return loanRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> ApiException.notFound("Loan not found"));
    }

    @Transactional
    public Loan update(String id, LoanRequest request, User user) {
        Loan l = getById(id, user);
        apply(l, request);
        return loanRepository.save(l);
    }

    @Transactional
    public void delete(String id, User user) {
        loanRepository.delete(getById(id, user));
    }

    private void apply(Loan l, LoanRequest request) {
        if (request.startDate() != null && request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw ApiException.badRequest("Loan end date cannot be before start date");
        }
        if (request.emiAmount() != null && request.principal() != null && request.emiAmount().compareTo(request.principal()) > 0) {
            throw ApiException.badRequest("Monthly EMI amount cannot exceed the total loan principal");
        }
        l.setName(request.name());
        l.setType(request.type());
        l.setPrincipal(request.principal());
        l.setEmiAmount(request.emiAmount());
        l.setInterestRate(request.interestRate());
        l.setStartDate(request.startDate());
        l.setEndDate(request.endDate());
        l.setActive(request.activeOrDefault());
    }
}
