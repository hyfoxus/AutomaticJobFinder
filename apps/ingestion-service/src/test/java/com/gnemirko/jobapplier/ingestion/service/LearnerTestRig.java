package com.gnemirko.jobapplier.ingestion.service;

import com.gnemirko.jobapplier.ingestion.util.UrlPolicy;

/** Gives the learner tests (other packages) a real polite fetcher on a fake clock. Never touches the live network. */
public final class LearnerTestRig {

    public final UrlPolicy policy;
    public final PoliteFetcher fetcher;

    public LearnerTestRig(String... overrideHosts) {
        StubSupport.FakeTime time = new StubSupport.FakeTime();
        StubSupport.Rig rig = new StubSupport.Rig(StubSupport.props(overrideHosts), time);
        this.policy = rig.policy;
        this.fetcher = rig.fetcher;
    }
}
