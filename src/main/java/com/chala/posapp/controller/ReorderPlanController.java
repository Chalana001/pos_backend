package com.chala.posapp.controller;
import com.chala.posapp.dto.reorder.*; import com.chala.posapp.service.ReorderPlanService; import lombok.RequiredArgsConstructor; import org.springframework.http.ResponseEntity; import org.springframework.security.access.prepost.PreAuthorize; import org.springframework.web.bind.annotation.*; import java.util.List;
@RestController @RequestMapping("/reorder-plans") @RequiredArgsConstructor @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','MANAGER')")
public class ReorderPlanController {private final ReorderPlanService service;
 @PostMapping public ResponseEntity<ReorderPlanResponse> create(@RequestBody ReorderPlanRequest r){return ResponseEntity.ok(service.create(r));}
 @GetMapping public ResponseEntity<List<ReorderPlanResponse>> list(){return ResponseEntity.ok(service.list());}
 @GetMapping("/{id}") public ResponseEntity<ReorderPlanResponse> get(@PathVariable Long id){return ResponseEntity.ok(service.get(id));}
 @PatchMapping("/{id}/lines/{lineId}") public ResponseEntity<ReorderPlanResponse> update(@PathVariable Long id,@PathVariable Long lineId,@RequestBody ReorderLineUpdateRequest r){return ResponseEntity.ok(service.updateLine(id,lineId,r));}
 @PostMapping("/{id}/submit") public ResponseEntity<ReorderPlanResponse> submit(@PathVariable Long id){return ResponseEntity.ok(service.submit(id));}
 @PostMapping("/{id}/approve") @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')") public ResponseEntity<ReorderPlanResponse> approve(@PathVariable Long id){return ResponseEntity.ok(service.approve(id));}
 @PostMapping("/{id}/reject") @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')") public ResponseEntity<ReorderPlanResponse> reject(@PathVariable Long id,@RequestParam String reason){return ResponseEntity.ok(service.reject(id,reason));}
 @GetMapping("/{id}/purchase-drafts") public ResponseEntity<List<PurchaseDraftResponse>> drafts(@PathVariable Long id){return ResponseEntity.ok(service.purchaseDrafts(id));}
 @PostMapping("/{id}/mark-handoff-complete") public ResponseEntity<ReorderPlanResponse> converted(@PathVariable Long id,@RequestBody MarkHandoffRequest request){return ResponseEntity.ok(service.markConverted(id,request));}
}
