package com.finguard.backend.service;

import com.finguard.backend.dto.InvestmentDTO;
import com.finguard.backend.model.Investment;
import com.finguard.backend.model.User;
import com.finguard.backend.repository.InvestmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class InvestmentService {

    private final InvestmentRepository investmentRepository;

    public Investment create(InvestmentDTO dto, User user) {
        Investment i = new Investment();
        i.setUser(user);
        i.setName(dto.getName());
        i.setType(dto.getType());
        i.setAmountInvested(dto.getAmountInvested());
        i.setCurrentValue(dto.getCurrentValue());
        i.setNotes(dto.getNotes());
        return investmentRepository.save(i);
    }

    public List<Investment> getAll(User user) {
        return investmentRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    public Investment getById(String id) {
        return investmentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Investment not found"));
    }

    public Investment update(String id, InvestmentDTO dto) {
        Investment i = getById(id);
        i.setName(dto.getName());
        i.setType(dto.getType());
        i.setAmountInvested(dto.getAmountInvested());
        i.setCurrentValue(dto.getCurrentValue());
        i.setNotes(dto.getNotes());
        return investmentRepository.save(i);
    }

    public void delete(String id) {
        investmentRepository.deleteById(id);
    }
}