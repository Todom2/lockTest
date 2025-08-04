package org.example.locktest.trip;

import lombok.RequiredArgsConstructor;
import org.example.locktest.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TripController {
    private final SettlementService settlementService;
    @PostMapping("/settlement")
    public ResponseEntity<ApiResponse<?>> settle(@RequestBody SettlementRequestDto dto){
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.of(settlementService.settle(dto)));
    }

    @PostMapping("/settlement2")
    public ResponseEntity<ApiResponse<?>> settle2(@RequestBody SettlementRequestDto dto){
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.of(settlementService.settle2(dto)));
    }
}
