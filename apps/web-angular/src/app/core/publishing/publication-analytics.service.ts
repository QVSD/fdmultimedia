import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { PublicationAnalyticsSnapshot, PublicationAnalyticsState, PublicationAttribution } from './publication-analytics.models';
import { DashboardBreakdown, DashboardDimension, DashboardFilters, DashboardMetric, DashboardOptions, DashboardSummary, DashboardTrend } from './publication-dashboard.models';
import { CompareRequest, ComparisonResult, InsightsResponse } from './publication-insights.models';
import { CampaignCohortComparison, CampaignDimension, CampaignOption, CampaignReview, CampaignReviewListItem, CampaignWindow } from './campaign-performance.models';
import { OptimizationEligibility, OptimizationProposal, OptimizationProposalOrigin } from './optimization-proposal.models';
import { AdaptiveGuardrailEvaluation, RobotChangeEligibility, RobotChangeProposal } from './robot-change-proposal.models';

@Injectable({ providedIn: 'root' })
export class PublicationAnalyticsService {
  constructor(private readonly http: HttpClient) {}

  private dashboardParams(filters: object): Record<string, string> {
    return Object.fromEntries(Object.entries(filters).filter(([, value]) => value != null && value !== '')
      .map(([key, value]) => [key, String(value)])) as Record<string, string>;
  }

  dashboardSummary(filters: DashboardFilters): Observable<DashboardSummary> {
    return this.http.get<DashboardSummary>(`${environment.apiBaseUrl}/analytics/dashboard/summary`,
      { params: this.dashboardParams(filters), withCredentials: true });
  }

  dashboardTrend(filters: DashboardFilters, metric: DashboardMetric): Observable<DashboardTrend> {
    return this.http.get<DashboardTrend>(`${environment.apiBaseUrl}/analytics/dashboard/trend`,
      { params: { ...this.dashboardParams(filters), metric }, withCredentials: true });
  }

  dashboardBreakdown(filters: DashboardFilters, dimension: DashboardDimension): Observable<DashboardBreakdown> {
    return this.http.get<DashboardBreakdown>(`${environment.apiBaseUrl}/analytics/dashboard/breakdown`,
      { params: { ...this.dashboardParams(filters), dimension }, withCredentials: true });
  }

  dashboardOptions(filters: DashboardFilters): Observable<DashboardOptions> {
    return this.http.get<DashboardOptions>(`${environment.apiBaseUrl}/analytics/dashboard/filters`,
      { params: this.dashboardParams(filters), withCredentials: true });
  }

  history(id: string): Observable<PublicationAnalyticsSnapshot[]> {
    return this.http.get<PublicationAnalyticsSnapshot[]>(
      `${environment.apiBaseUrl}/publications/${id}/analytics`, { withCredentials: true });
  }

  state(id: string): Observable<PublicationAnalyticsState | null> {
    return this.http.get<PublicationAnalyticsState | null>(
      `${environment.apiBaseUrl}/publications/${id}/analytics/state`, { withCredentials: true });
  }

  attribution(id: string): Observable<PublicationAttribution> {
    return this.http.get<PublicationAttribution>(
      `${environment.apiBaseUrl}/publications/${id}/attribution`, { withCredentials: true });
  }

  refresh(id: string): Observable<PublicationAnalyticsSnapshot> {
    return this.http.post<PublicationAnalyticsSnapshot>(
      `${environment.apiBaseUrl}/publications/${id}/analytics/refresh`, {}, { withCredentials: true });
  }

  insights(filters: DashboardFilters): Observable<InsightsResponse> {
    return this.http.get<InsightsResponse>(`${environment.apiBaseUrl}/analytics/insights`,
      { params: this.dashboardParams(filters), withCredentials: true });
  }

  compareSegments(request: CompareRequest): Observable<ComparisonResult> {
    const { dimension, leftSegmentId, rightSegmentId, metric, statistic, ...filters } = request;
    const params: Record<string, string> = {
      ...this.dashboardParams(filters), dimension, leftSegmentId, rightSegmentId, metric,
    };
    if (statistic) {
      params['statistic'] = statistic;
    }
    return this.http.get<ComparisonResult>(`${environment.apiBaseUrl}/analytics/insights/compare`,
      { params, withCredentials: true });
  }

  campaignOptions(limit = 50): Observable<CampaignOption[]> {
    return this.http.get<CampaignOption[]>(`${environment.apiBaseUrl}/analytics/campaign-performance/campaigns`,
      { params: { limit }, withCredentials: true });
  }

  campaignReviews(runId: string): Observable<CampaignReviewListItem[]> {
    return this.http.get<CampaignReviewListItem[]>(`${environment.apiBaseUrl}/robot-runs/${runId}/performance-reviews`,
      { withCredentials: true });
  }

  campaignReview(id: string): Observable<CampaignReview> {
    return this.http.get<CampaignReview>(`${environment.apiBaseUrl}/campaign-performance-reviews/${id}`,
      { withCredentials: true });
  }

  createCampaignReview(runId: string, observationWindow: CampaignWindow, metric: DashboardMetric): Observable<CampaignReview> {
    return this.http.post<CampaignReview>(`${environment.apiBaseUrl}/robot-runs/${runId}/performance-reviews`,
      { observationWindow, metric }, { withCredentials: true });
  }

  campaignComparison(filters: { dateFrom: string; dateTo: string; observationWindow: CampaignWindow;
    metric: DashboardMetric; dimension: CampaignDimension; provider?: string }): Observable<CampaignCohortComparison> {
    return this.http.get<CampaignCohortComparison>(`${environment.apiBaseUrl}/analytics/campaign-performance/comparison`,
      { params: this.dashboardParams(filters), withCredentials: true });
  }

  optimizationEligibility(reviewId: string): Observable<OptimizationEligibility> {
    return this.http.get<OptimizationEligibility>(`${environment.apiBaseUrl}/optimization-proposals/eligibility/${reviewId}`,
      { withCredentials: true });
  }

  optimizationProposals(limit = 50, origin?: OptimizationProposalOrigin): Observable<OptimizationProposal[]> {
    return this.http.get<OptimizationProposal[]>(`${environment.apiBaseUrl}/optimization-proposals`,
      { params: origin ? { limit, origin } : { limit }, withCredentials: true });
  }

  createOptimizationProposal(sourceReviewId: string, candidatePersonaId: string): Observable<OptimizationProposal> {
    return this.http.post<OptimizationProposal>(`${environment.apiBaseUrl}/optimization-proposals`,
      { sourceReviewId, candidatePersonaId }, { withCredentials: true });
  }

  approveOptimizationProposal(id: string): Observable<OptimizationProposal> {
    return this.http.post<OptimizationProposal>(`${environment.apiBaseUrl}/optimization-proposals/${id}/approve`, {}, { withCredentials: true });
  }

  rejectOptimizationProposal(id: string): Observable<OptimizationProposal> {
    return this.http.post<OptimizationProposal>(`${environment.apiBaseUrl}/optimization-proposals/${id}/reject`, {}, { withCredentials: true });
  }

  materializeOptimizationProposal(id: string): Observable<OptimizationProposal> {
    return this.http.post<OptimizationProposal>(`${environment.apiBaseUrl}/optimization-proposals/${id}/materialize-experiment`, {}, { withCredentials: true });
  }

  robotChangeEligibility(sourceOptimizationProposalId: string, targetRobotId: string): Observable<RobotChangeEligibility> {
    return this.http.get<RobotChangeEligibility>(`${environment.apiBaseUrl}/robot-change-proposals/eligibility`,
      { params: { sourceOptimizationProposalId, targetRobotId }, withCredentials: true });
  }

  robotChangeProposals(limit = 50): Observable<RobotChangeProposal[]> {
    return this.http.get<RobotChangeProposal[]>(`${environment.apiBaseUrl}/robot-change-proposals`,
      { params: { limit }, withCredentials: true });
  }

  createRobotChangeProposal(sourceOptimizationProposalId: string, targetRobotId: string): Observable<RobotChangeProposal> {
    return this.http.post<RobotChangeProposal>(`${environment.apiBaseUrl}/robot-change-proposals`,
      { sourceOptimizationProposalId, targetRobotId }, { withCredentials: true });
  }

  approveRobotChangeProposal(id: string): Observable<RobotChangeProposal> {
    return this.http.post<RobotChangeProposal>(`${environment.apiBaseUrl}/robot-change-proposals/${id}/approve`, {}, { withCredentials: true });
  }

  rejectRobotChangeProposal(id: string): Observable<RobotChangeProposal> {
    return this.http.post<RobotChangeProposal>(`${environment.apiBaseUrl}/robot-change-proposals/${id}/reject`, {}, { withCredentials: true });
  }

  applyRobotChangeProposal(id: string): Observable<RobotChangeProposal> {
    return this.http.post<RobotChangeProposal>(`${environment.apiBaseUrl}/robot-change-proposals/${id}/apply`, {}, { withCredentials: true });
  }

  robotChangeGuardrails(id: string): Observable<AdaptiveGuardrailEvaluation> {
    return this.http.get<AdaptiveGuardrailEvaluation>(`${environment.apiBaseUrl}/robot-change-proposals/${id}/guardrails`, { withCredentials: true });
  }
}
