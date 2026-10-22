package com.finguard.backend.ledger.investments;

import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.identity.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class InvestmentService {

    private final InvestmentRepository investmentRepository;

    public Investment create(InvestmentRequest request, User user) {
        Investment i = new Investment();
        i.setUser(user);
        apply(i, request);
        return investmentRepository.save(i);
    }

    @Transactional(readOnly = true)
    public List<Investment> getAll(User user) {
        return investmentRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    @Transactional(readOnly = true)
    public Investment getById(String id, User user) {
        return investmentRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> ApiException.notFound("Investment not found"));
    }

    @Transactional
    public Investment update(String id, InvestmentRequest request, User user) {
        Investment i = getById(id, user);
        apply(i, request);
        return investmentRepository.save(i);
    }

    @Transactional
    public void delete(String id, User user) {
        investmentRepository.delete(getById(id, user));
    }

    private void apply(Investment i, InvestmentRequest request) {
        i.setName(request.name());
        i.setType(request.type());
        i.setAmountInvested(request.amountInvested());
        i.setCurrentValue(request.currentValue());
        i.setNotes(request.notes());
    }
}
