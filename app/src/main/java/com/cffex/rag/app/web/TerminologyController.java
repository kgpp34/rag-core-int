package com.cffex.rag.app.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cffex.rag.common.domain.terminology.TerminologyStatus;
import com.cffex.rag.common.service.TerminologyService;
import com.cffex.rag.metadatacacher.infrastructure.job.TerminologySnapshotRefresher;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 术语规则运行状态与运维刷新接口。 */
@RestController
@RequestMapping("/api/v1/terminology")
@Tag(name = "Terminology", description = "术语替换运行状态与规则刷新")
public class TerminologyController {

    private final TerminologyService terminologyService;
    private final ObjectProvider<TerminologySnapshotRefresher> refresherProvider;

    public TerminologyController(
            TerminologyService terminologyService,
            ObjectProvider<TerminologySnapshotRefresher> refresherProvider
    ) {
        this.terminologyService = terminologyService;
        this.refresherProvider = refresherProvider;
    }

    @GetMapping(path = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "查询术语规则状态", description = "返回启用状态、快照版本、规则数量和最近加载错误。")
    public TerminologyStatus status() {
        return terminologyService.status();
    }

    @PostMapping(path = "/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "立即刷新术语规则", description = "特性启用时立即从主业务库重新加载规则。")
    public TerminologyStatus refresh() {
        TerminologySnapshotRefresher refresher = refresherProvider.getIfAvailable();
        if (refresher != null) {
            refresher.refresh();
        }
        return terminologyService.status();
    }
}
