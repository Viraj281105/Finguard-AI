package com.finguard.backend.service;

import com.finguard.backend.dto.LoanDTO;
import com.finguard.backend.model.Loan;
import com.finguard.backend.model.User;
import com.finguard.backend.repository.LoanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LoanService {

    private final LoanRepository loanRepository;

    public Loan create(LoanDTO dto, User user) {
        Loan l = new Loan();
        l.setUser(user);
        l.setName(dto.getName());
        l.setType(dto.getType());
        l.setPrincipal(dto.getPrincipal());
        l.setEmiAmount(dto.getEmiAmount());
        l.setInterestRate(dto.getInterestRate());
        l.setStartDate(dto.getStartDate());
        l.setEndDate(dto.getEndDate());
        l.setActive(dto.getActive());
        return loanRepository.save(l);
    }

    public List<Loan> getAll(User user) {
        return loanRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    public Loan getById(String id) {
        return loanRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Loan not found"));
    }

    public Loan update(String id, LoanDTO dto) {
        Loan l = getById(id);
        l.setName(dto.getName());
        l.setType(dto.getType());
        l.setPrincipal(dto.getPrincipal());
        l.setEmiAmount(dto.getEmiAmount());
        l.setInterestRate(dto.getInterestRate());
        l.setStartDate(dto.getStartDate());
        l.setEndDate(dto.getEndDate());
        l.setActive(dto.getActive());
        return loanRepository.save(l);
    }

    public void delete(String id) {
        loanRepository.deleteById(id);
    }
}