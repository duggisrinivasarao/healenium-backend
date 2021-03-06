package com.epam.healenium.service.impl;

import com.epam.healenium.config.PrometheusConfiguration;
import com.epam.healenium.exception.MissingSelectorException;
import com.epam.healenium.mapper.HealingMapper;
import com.epam.healenium.mapper.SelectorMapper;
import com.epam.healenium.model.domain.Healing;
import com.epam.healenium.model.domain.HealingResult;
import com.epam.healenium.model.domain.Selector;
import com.epam.healenium.model.dto.HealingDto;
import com.epam.healenium.model.dto.HealingRequestDto;
import com.epam.healenium.model.dto.HealingResultDto;
import com.epam.healenium.model.dto.RequestDto;
import com.epam.healenium.model.dto.SelectorRequestDto;
import com.epam.healenium.repository.HealingRepository;
import com.epam.healenium.repository.HealingResultRepository;
import com.epam.healenium.repository.ReportRepository;
import com.epam.healenium.repository.SelectorRepository;
import com.epam.healenium.service.HealingService;
import com.epam.healenium.specification.HealingSpecBuilder;
import com.epam.healenium.treecomparing.Node;
import com.epam.healenium.util.StreamUtils;
import com.epam.healenium.util.Utils;
import io.prometheus.client.SimpleTimer;
import io.prometheus.client.Summary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.io.FileHandler;
import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusPushGatewayManager;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.transaction.Transactional;
import java.io.File;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.epam.healenium.constants.Constants.SESSION_KEY;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class HealingServiceImpl implements HealingService {

    private final HealingRepository healingRepository;
    private final SelectorRepository selectorRepository;
    private final HealingResultRepository resultRepository;
    private final ReportRepository reportRepository;

    private final SelectorMapper selectorMapper;
    private final HealingMapper healingMapper;

    private final PrometheusConfiguration prometheusConfiguration;

    private static final Summary healLatency = Summary.build()
            .name("healing_latency")
            .help("Duration in seconds of healing")
            .register();

    @Override
    public void saveSelector(SelectorRequestDto request) {
        final Selector selector = selectorMapper.dtoToDocument(request);
        selectorRepository.save(selector);
    }

    @Override
    public List<Node> getSelectorPath(RequestDto dto) {
        String selectorId = Utils.buildKey(dto.getClassName(), dto.getMethodName(), dto.getLocator());
        return selectorRepository.findById(selectorId)
                .map(t -> t.getNodePathWrapper().getNodePath())
                .orElse(Collections.emptyList());
    }

    @Override
    public void saveHealing(HealingRequestDto dto, MultipartFile screenshot, Map<String, String> headers) {
        PrometheusPushGatewayManager prometheusPushGatewayManager = prometheusConfiguration.prometheusPushGatewayManager(headers);
        SimpleTimer requestTimer = new SimpleTimer();
        // obtain healing
        Healing healing = getHealing(dto);
        // collect healing results
        Collection<HealingResult> healingResults = buildHealingResults(dto.getResults(), healing);
        HealingResult selectedResult = healingResults.stream()
                .filter(it -> {
                    String firstLocator, secondLocator;
                    firstLocator = it.getLocator().getValue();
                    secondLocator = dto.getUsedResult().getLocator().getValue();
                    return firstLocator.equals(secondLocator);
                })
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Internal exception! Somehow we lost selected healing result on save"));
        // add report record
        createReportRecord(selectedResult, healing, headers.get(SESSION_KEY), screenshot);

        healLatency.observe(requestTimer.elapsedSeconds());
        prometheusPushGatewayManager.shutdown();
    }

    @Override
    public Set<HealingDto> getHealings(RequestDto dto) {
        Set<HealingDto> result = new HashSet<>();
        healingRepository.findAll(HealingSpecBuilder.buildSpec(dto)).stream()
                .collect(Collectors.groupingBy(Healing::getSelector))
                .forEach((selector, healingList) -> {
                    // collect healing results
                    Set<HealingResultDto> healingResults = healingList.stream()
                            .flatMap(it -> it.getResults().stream())
                            .sorted(Comparator.comparing(HealingResult::getScore, Comparator.reverseOrder()))
                            .filter(StreamUtils.distinctByKey(HealingResult::getLocator))
                            .map(healingMapper::modelToResultDto)
                            .collect(Collectors.toSet());
                    // build healing dto
                    HealingDto healingDto = new HealingDto()
                            .setClassName(selector.getClassName())
                            .setMethodName(selector.getMethodName())
                            .setLocator(selector.getLocator().getValue())
                            .setResults(healingResults);
                    // add dto to result collection
                    result.add(healingDto);
                });
        return result;
    }

    @Override
    public Set<HealingResultDto> getHealingResults(RequestDto dto) {
        String selectorId = Utils.buildKey(dto.getClassName(), dto.getMethodName(), dto.getLocator());
        return healingRepository.findBySelectorId(selectorId).stream()
                .flatMap(it -> healingMapper.modelToResultDto(it.getResults()).stream())
                .collect(Collectors.toSet());
    }

    private Healing getHealing(HealingRequestDto dto) {
        // build selector key
        String selectorId = Utils.buildKey(dto.getClassName(), dto.getMethodName(), dto.getLocator());
        // build healing key
        String healingId = Utils.buildHealingKey(selectorId, dto.getPageContent());
        return healingRepository.findById(healingId).orElseGet(() -> {
            // if no healing present
            Optional<Selector> optionalSelector = selectorRepository.findById(selectorId);
            return optionalSelector.map(element -> healingRepository.save(new Healing(healingId, element, dto.getPageContent())))
                    .orElseThrow(MissingSelectorException::new);
        });
    }

    private List<HealingResult> buildHealingResults(List<HealingResultDto> dtos, Healing healing) {
        List<HealingResult> results = dtos.stream().map(healingMapper::resultDtoToModel).peek(it -> it.setHealing(healing)).collect(Collectors.toList());
        return resultRepository.saveAll(results);
    }

    /**
     * Persist healing results
     *
     * @param healing
     * @param healingResults
     * @return
     */
    private void saveHealingResults(Collection<HealingResult> healingResults, Healing healing) {
        if (!CollectionUtils.isEmpty(healing.getResults())) {
            // remove old results for given healing object
            resultRepository.deleteAll(healing.getResults());
        }

        // save new results
        List<HealingResult> results = resultRepository.saveAll(healingResults);
    }

    /**
     * Create record in report about healing
     *
     * @param result
     * @param healing
     * @param sessionId
     */
    private void createReportRecord(HealingResult result, Healing healing, String sessionId, MultipartFile screenshot) {
        if (!StringUtils.isEmpty(sessionId)) {
            String screenshotDir = "/screenshots/" + sessionId;
            String screenshotPath = persistScreenshot(screenshot, screenshotDir);
            // if healing performs during test phase, add report record
            reportRepository.findById(sessionId).ifPresent(r -> {
                r.addRecord(healing, result, screenshotPath);
                reportRepository.save(r);
            });
        }
    }

    /**
     * @param file
     * @param filePath
     */
    private String persistScreenshot(MultipartFile file, String filePath) {
        String rootDir = Paths.get("").toAbsolutePath().toString();
        String baseDir = Paths.get(rootDir, filePath).toString();
        try {
            FileHandler.createDir(new File(baseDir));
            file.transferTo(Paths.get(baseDir, file.getOriginalFilename()));
        } catch (Exception ex) {
            log.warn("Failed to save screenshot {} in {}", file.getOriginalFilename(), baseDir);
        }
        return Paths.get(filePath, file.getOriginalFilename()).toString();
    }
}
