package com.example.llmshadow.controller;

import com.example.llmshadow.persistence.MismatchRecord;
import com.example.llmshadow.persistence.MismatchRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mismatches")
@Tag(name = "Mismatches", description = "Review recently persisted shadow mismatches.")
public class MismatchController {

    private final MismatchRepository mismatchRepository;

    public MismatchController(MismatchRepository mismatchRepository) {
        this.mismatchRepository = mismatchRepository;
    }

    @GetMapping
    @Operation(summary = "List recent persisted mismatches")
    public List<MismatchRecord> recentMismatches(@RequestParam(defaultValue = "50") int limit) {
        int sanitizedLimit = Math.max(1, Math.min(limit, 500));
        return mismatchRepository.findRecent(sanitizedLimit);
    }
}
