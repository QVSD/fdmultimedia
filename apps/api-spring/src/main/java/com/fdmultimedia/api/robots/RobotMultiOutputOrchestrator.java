package com.fdmultimedia.api.robots;

import com.fdmultimedia.api.accounts.*;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.contentdrafts.*;
import com.fdmultimedia.api.contentsuggestions.*;
import com.fdmultimedia.api.experiments.*;
import com.fdmultimedia.api.highlights.*;
import com.fdmultimedia.api.publishschedules.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class RobotMultiOutputOrchestrator {
    private final HighlightAnalysisRepository analyses; private final HighlightService highlightService;
    private final HighlightProperties properties; private final HighlightSelectionService selectionService;
    private final HighlightSelectionRepository selections; private final HighlightSelectionItemRepository selectionItems;
    private final RobotRunOutputRepository outputs; private final ContentDraftService draftService; private final ContentDraftRepository drafts;
    private final ContentSuggestionService suggestionService; private final ContentSuggestionRepository suggestions;
    private final RobotApprovalRepository approvals; private final PublishScheduleService schedules; private final ExperimentService experiments;
    RobotMultiOutputOrchestrator(HighlightAnalysisRepository analyses,HighlightService highlightService,HighlightProperties properties,
      HighlightSelectionService selectionService,HighlightSelectionRepository selections,HighlightSelectionItemRepository selectionItems,
      RobotRunOutputRepository outputs,ContentDraftService draftService,ContentDraftRepository drafts,
      ContentSuggestionService suggestionService,ContentSuggestionRepository suggestions,RobotApprovalRepository approvals,
      PublishScheduleService schedules,ExperimentService experiments){this.analyses=analyses;this.highlightService=highlightService;this.properties=properties;
      this.selectionService=selectionService;this.selections=selections;this.selectionItems=selectionItems;this.outputs=outputs;this.draftService=draftService;
      this.drafts=drafts;this.suggestionService=suggestionService;this.suggestions=suggestions;this.approvals=approvals;this.schedules=schedules;this.experiments=experiments;}

    void advance(RobotRun run,AuthenticatedUser principal,Instant now){
      if(run.getHighlightSelectionId()==null){ if(!ensureSelection(run,principal,now)) return; }
      HighlightSelection selection=selections.findByWorkspaceAndId(run.getWorkspace(),run.getHighlightSelectionId()).orElse(null);
      if(selection==null){run.markFailed("HIGHLIGHT_SELECTION_MISSING","The diversity selection is unavailable",now);return;}
      List<HighlightSelectionItem> items=selectionItems.findBySelectionOrderBySelectionOrderAsc(selection);
      if(items.isEmpty()){run.markFailed("NO_DIVERSE_HIGHLIGHTS","No candidates met the diversity rules",now);return;}
      List<RobotRunOutput> existing=outputs.findByRobotRunOrderBySelectionOrderAsc(run);
      Set<UUID> existingItems=new HashSet<>(); existing.forEach(o->existingItems.add(o.getSelectionItem().getId()));
      for(HighlightSelectionItem item:items) if(!existingItems.contains(item.getId())) outputs.save(new RobotRunOutput(run,selection,item,now));
      List<RobotRunOutput> all=outputs.findByRobotRunOrderBySelectionOrderAsc(run);
      for(RobotRunOutput output:all) if(!output.getStatus().terminal()) try{advanceOutput(run,output,principal,now);}catch(RuntimeException ex){output.failed("OUTPUT_RECONCILIATION_FAILED","Output could not be advanced",now);}
      aggregate(run,all,now);
    }

    private boolean ensureSelection(RobotRun run,AuthenticatedUser principal,Instant now){
      String v3=properties.getV3AnalyzerType();
      if(run.getHighlightAnalysisId()==null){
        List<HighlightAnalysis> rows=analyses.findByWorkspaceAndAssetOrderByCreatedAtDesc(run.getWorkspace(),run.getSourceAsset());
        HighlightAnalysis found=rows.stream().filter(a->v3.equals(a.getRequestedAnalyzerType())).filter(a->a.getStatus()!=HighlightAnalysisStatus.FAILED).findFirst().orElse(null);
        if(found==null){var created=highlightService.createAnalysis(principal,run.getSourceAsset().getId(),new CreateHighlightAnalysisRequest(v3));run.setHighlightAnalysisId(created.id());return false;}
        run.setHighlightAnalysisId(found.getId());
      }
      HighlightAnalysis analysis=analyses.findById(run.getHighlightAnalysisId()).orElse(null);
      if(analysis==null||analysis.getStatus()==HighlightAnalysisStatus.FAILED){run.markFailed("V3_ANALYSIS_FAILED","A successful V3 analysis is required",now);return false;}
      if(analysis.getStatus()!=HighlightAnalysisStatus.SUCCEEDED)return false;
      if(!v3.equals(analysis.getAnalyzerType())){run.markFailed("V3_ANALYSIS_UNAVAILABLE","V3 fell back and cannot power diversity selection",now);return false;}
      HighlightSelectionSummary summary=selectionService.create(principal,analysis.getId(),new CreateHighlightSelectionRequest(run.getRequestedOutputCount()));
      run.bindSelection(summary.id(),summary.selectedCount());
      if(summary.selectedCount()==0){run.markFailed("NO_DIVERSE_HIGHLIGHTS","No candidates met the diversity rules",now);return false;}
      return true;
    }

    private void advanceOutput(RobotRun run,RobotRunOutput out,AuthenticatedUser principal,Instant now){
      switch(out.getStatus()){
        case CREATED->{var d=draftService.createForRobotOutput(principal,out.getSelectionItem(),out.getId(),run.getId());out.waitingForDraft(d.id(),now);}
        case WAITING_FOR_DRAFT->{var d=draftService.getFor(principal,out.getContentDraftId()); if(d.status()==ContentDraftStatus.FAILED)out.failed("OUTPUT_DRAFT_FAILED",d.failureMessage(),now); else if(d.status()==ContentDraftStatus.READY){if(run.getAiPolicySnapshot()==RobotAiPolicy.NO_AI)autonomy(run,out,d,principal,now);else beginAi(run,out,d,now);}}
        case WAITING_FOR_AI->{ContentSuggestion s=suggestions.findById(out.getContentSuggestionId()).orElse(null); if(s==null){out.failed("OUTPUT_AI_FAILED","Suggestion unavailable",now);break;} switch(s.getStatus()){case FAILED,DISCARDED->out.failed("OUTPUT_AI_FAILED",s.getFailureMessage(),now);case READY->{if(run.getAiPolicySnapshot()==RobotAiPolicy.GENERATE_FOR_REVIEW)out.waitingForAiReview(now);else{suggestionService.apply(principal,s.getId());autonomy(run,out,draftService.getFor(principal,out.getContentDraftId()),principal,now);}}case APPLIED->autonomy(run,out,draftService.getFor(principal,out.getContentDraftId()),principal,now);default->{}}}
        case WAITING_FOR_AI_REVIEW->{ContentSuggestion s=suggestions.findById(out.getContentSuggestionId()).orElse(null);if(s!=null&&s.getStatus()==ContentSuggestionStatus.APPLIED)autonomy(run,out,draftService.getFor(principal,out.getContentDraftId()),principal,now);else if(s!=null&&(s.getStatus()==ContentSuggestionStatus.DISCARDED||s.getStatus()==ContentSuggestionStatus.FAILED))out.failed("OUTPUT_AI_FAILED",s.getFailureMessage(),now);}
        case SCHEDULED->out.succeeded(now);
        default->{}
      }
    }
    private void beginAi(RobotRun run,RobotRunOutput out,ContentDraftSummary draft,Instant now){ContentDraft entity=drafts.findByWorkspaceAndId(run.getWorkspace(),draft.id()).orElseThrow();ExperimentTreatment treatment=run.getExperimentVariantId()==null?null:experiments.resolveTreatment(run.getExperimentId(),run.getExperimentAssignmentId(),run.getExperimentVariantId());var s=suggestionService.createForRobotOutput(run.getWorkspace(),entity,run.getPersonaIdSnapshot(),run.getAiLanguageOverrideSnapshot(),run.getAiToneOverrideSnapshot(),run.getId(),out.getId(),run.getRobot().getCreatedByUser(),treatment);out.waitingForAi(s.id(),now);}
    private void autonomy(RobotRun run,RobotRunOutput out,ContentDraftSummary draft,AuthenticatedUser principal,Instant now){Robot robot=run.getRobot();switch(robot.getAutonomyMode()){
      case DRAFT_ONLY->out.succeeded(now);
      case REVIEW_REQUIRED->{RobotApproval a=approvals.findByRobotRunOutputId(out.getId()).orElse(null);if(a==null){SocialAccount account=robot.getTargetSocialAccount();if(account==null){out.failed("SOCIAL_ACCOUNT_UNAVAILABLE","Robot has no target account",now);return;}Instant proposed=scheduledFor(run,robot,out,now);a=approvals.save(new RobotApproval(run.getWorkspace(),run,out,draft.id(),account.getId(),proposed,now));}out.waitingForReview(a.getId(),now);}
      case AUTO_SCHEDULE->{SocialAccount account=robot.getTargetSocialAccount();if(account==null||account.getPlatform()!=SocialPlatform.TEST){out.failed("AUTONOMOUS_PROVIDER_NOT_ALLOWED","Automatic scheduling requires TEST",now);return;}Instant when=scheduledFor(run,robot,out,now);var s=schedules.create(principal,draft.id(),new CreatePublishScheduleRequest(account.getId(),when));out.scheduled(s.id(),now);}
    }}
    private Instant scheduledFor(RobotRun run,Robot robot,RobotRunOutput out,Instant now){return run.outputScheduleBase(now,robot.getScheduleDelayMinutes()).plus((long)(out.getSelectionOrder()-1)*run.getOutputSpacingMinutesSnapshot(),java.time.temporal.ChronoUnit.MINUTES);}
    void aggregate(RobotRun run,List<RobotRunOutput> rows,Instant now){if(rows.isEmpty())return;if(rows.stream().anyMatch(o->!o.getStatus().terminal()))return;long ok=rows.stream().filter(o->o.getStatus()==RobotRunOutputStatus.SUCCEEDED).count();if(ok==rows.size())run.markSucceeded(now);else if(ok>0)run.markPartiallySucceeded(now);else run.markFailed("ALL_OUTPUTS_FAILED","No output completed successfully",now);}
}
