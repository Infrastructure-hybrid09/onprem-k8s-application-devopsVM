(() => {
  "use strict";

  const storageKey = "neuroplan-mvp-demo-v2";
  const apiConfig = { enabled: false, baseUrl: "/api", ...(window.NEUROPLAN_API || {}) };
  const fallbackSubjects = [
    { id: 1, code: "LINUX", name: "Linux" },
    { id: 2, code: "NETWORK", name: "Network" },
    { id: 3, code: "KUBERNETES", name: "Kubernetes" },
    { id: 4, code: "DATABASE", name: "Database" },
    { id: 5, code: "CLOUD", name: "Cloud" },
    { id: 6, code: "DEVOPS", name: "DevOps" }
  ];
  const subjectDescriptions = {
    LINUX: "서버 운영 기초",
    NETWORK: "통신과 보안",
    KUBERNETES: "컨테이너 오케스트레이션",
    DATABASE: "SQL과 데이터 모델링",
    CLOUD: "클라우드 인프라",
    DEVOPS: "CI/CD와 자동화"
  };
  const levelToApi = { "초급": "BEGINNER", "중급": "INTERMEDIATE", "고급": "ADVANCED" };
  const defaultState = {
    authenticated: false,
    userName: "",
    userEmail: "",
    isAdmin: false,
    subjects: [],
    subjectLevels: {},
    planId: null,
    planSteps: [],
    planGenerated: false,
    tasks: [false, false, false],
    quizFinished: false,
    quizCorrect: 0,
    quizTotal: 0,
    stats: { solvedCount: 0, correctCount: 0, completedStepCount: 0 }
  };
  const fallbackQuestions = [
    {
      id: 1, subjectName: "Kubernetes", difficulty: "BEGINNER",
      text: "동일한 애플리케이션 Pod의 복제본 수를 유지하는 리소스는 무엇인가요?",
      options: [{ id: 11, text: "Service" }, { id: 12, text: "ReplicaSet" }, { id: 13, text: "ConfigMap" }, { id: 14, text: "Namespace" }],
      correctOptionId: 12,
      explanation: "ReplicaSet은 지정한 수의 Pod 복제본이 계속 실행되도록 유지합니다."
    },
    {
      id: 2, subjectName: "Kubernetes", difficulty: "BEGINNER",
      text: "여러 Pod에 하나의 고정 접근 지점을 제공하는 리소스는 무엇인가요?",
      options: [{ id: 21, text: "Service" }, { id: 22, text: "Secret" }, { id: 23, text: "CronJob" }, { id: 24, text: "PersistentVolume" }],
      correctOptionId: 21,
      explanation: "Service는 변경될 수 있는 Pod 집합 앞에 안정적인 가상 IP와 DNS 이름을 제공합니다."
    },
    {
      id: 3, subjectName: "Kubernetes", difficulty: "BEGINNER",
      text: "선언적 배포와 롤링 업데이트를 관리하는 리소스는 무엇인가요?",
      options: [{ id: 31, text: "Deployment" }, { id: 32, text: "Node" }, { id: 33, text: "Namespace" }, { id: 34, text: "EndpointSlice" }],
      correctOptionId: 31,
      explanation: "Deployment는 ReplicaSet을 통해 Pod 배포와 롤링 업데이트를 관리합니다."
    },
    {
      id: 4, subjectName: "Kubernetes", difficulty: "BEGINNER",
      text: "민감하지 않은 설정값을 키-값으로 저장하는 리소스는 무엇인가요?",
      options: [{ id: 41, text: "ConfigMap" }, { id: 42, text: "DaemonSet" }, { id: 43, text: "IngressClass" }, { id: 44, text: "Job" }],
      correctOptionId: 41,
      explanation: "ConfigMap은 애플리케이션 설정을 컨테이너 이미지와 분리해 저장합니다."
    },
    {
      id: 5, subjectName: "Kubernetes", difficulty: "BEGINNER",
      text: "컨테이너 워크로드가 실행되는 Kubernetes의 최소 배포 단위는 무엇인가요?",
      options: [{ id: 51, text: "Pod" }, { id: 52, text: "ServiceAccount" }, { id: 53, text: "Role" }, { id: 54, text: "StorageClass" }],
      correctOptionId: 51,
      explanation: "Pod는 Kubernetes에서 하나 이상의 컨테이너를 실행하는 최소 배포 단위입니다."
    }
  ];

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const escapeHtml = value => String(value ?? "").replace(/[&<>'"]/g, character => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;"
  })[character]);

  let state = loadState();
  let subjectCatalog = [...fallbackSubjects];
  let authMode = "signup";
  let draftSubjects = [];
  let draftSubjectLevels = {};
  let questions = [];
  let quizAnswers = [];
  let quizIndex = 0;
  let quizScore = 0;
  let chosenAnswer = null;
  let answerChecked = false;
  let toastTimer;
  let adminOverview = null;

  function loadState() {
    try {
      const saved = JSON.parse(localStorage.getItem(storageKey) || "{}");
      return {
        ...defaultState,
        ...saved,
        subjectLevels: { ...(saved.subjectLevels || {}) },
        tasks: Array.isArray(saved.tasks) ? saved.tasks.slice(0, 3) : [false, false, false],
        stats: { ...defaultState.stats, ...(saved.stats || {}) }
      };
    } catch (_) {
      return { ...defaultState, tasks: [false, false, false] };
    }
  }

  function saveState() {
    if (!apiConfig.enabled) localStorage.setItem(storageKey, JSON.stringify(state));
  }

  function subjectByCode(code) {
    return subjectCatalog.find(subject => subject.code === code) || { code, name: code };
  }

  function subjectName(code) {
    return subjectByCode(code).name;
  }

  function hasCompleteProfile(profile = state) {
    return profile.subjects.length > 0 && profile.subjects.every(code => Boolean(profile.subjectLevels[code]));
  }

  function profileLabel(profile = state) {
    return profile.subjects.map(code => `${subjectName(code)} · ${profile.subjectLevels[code]}`).join(", ");
  }

  function clearAuthFields() {
    $("#authForm").reset();
    $("#authName").value = "";
    $("#authEmail").value = "";
    $("#authPassword").value = "";
    $("#authMessage").textContent = "";
  }

  function openModal(id) {
    if (id === "authModal") {
      clearAuthFields();
      setTimeout(clearAuthFields, 0);
    }
    const modal = document.getElementById(id);
    modal.hidden = false;
    const firstInput = modal.querySelector("input, button");
    if (firstInput) setTimeout(() => firstInput.focus(), 0);
  }

  function closeModal(id) {
    document.getElementById(id).hidden = true;
    if (id === "authModal") clearAuthFields();
  }

  function showLearningPage() {
    $("#adminPage").hidden = true;
    $("#dashboard").hidden = false;
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  async function loadAdminOverview() {
    if (!state.isAdmin) return null;
    adminOverview = apiConfig.enabled
      ? await apiRequest("/admin/overview")
      : {
          adminUserId: 1,
          adminEmail: state.userEmail,
          users: { total: 1, active: 1, locked: 0, withdrawn: 0 },
          activeSubjects: subjectCatalog.length,
          todayPlans: state.planGenerated ? 1 : 0,
          todayAttempts: state.quizFinished ? 1 : 0,
          recentUsers: [{
            id: 1, email: state.userEmail, nickname: state.userName,
            accountStatus: "ACTIVE", subjectCount: state.subjects.length,
            createdAt: new Date().toISOString(), lastSessionAt: new Date().toISOString()
          }]
        };
    renderAdminOverview();
    return adminOverview;
  }

  async function detectAdmin() {
    if (!state.authenticated) {
      state.isAdmin = false;
      return;
    }
    if (!apiConfig.enabled) {
      state.isAdmin = state.userEmail.toLowerCase() === "admin@nplan.local";
      return;
    }
    try {
      adminOverview = await apiRequest("/admin/overview");
      state.isAdmin = true;
    } catch (error) {
      if (error.status !== 403) throw error;
      state.isAdmin = false;
      adminOverview = null;
    }
  }

  function formatDateTime(value) {
    if (!value) return "-";
    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime()) ? escapeHtml(value) : parsed.toLocaleString("ko-KR", { dateStyle: "short", timeStyle: "short" });
  }

  function renderAdminOverview() {
    if (!adminOverview) return;
    $("#adminIdentity").textContent = `${adminOverview.adminEmail} 관리자 · 활성 과목 ${adminOverview.activeSubjects}개`;
    $("#adminTotalUsers").textContent = String(adminOverview.users.total);
    $("#adminActiveUsers").textContent = String(adminOverview.users.active);
    $("#adminTodayPlans").textContent = String(adminOverview.todayPlans);
    $("#adminTodayAttempts").textContent = String(adminOverview.todayAttempts);
    $("#adminUsersBody").innerHTML = adminOverview.recentUsers.length
      ? adminOverview.recentUsers.map(user => `
          <tr>
            <td><strong>${escapeHtml(user.nickname)}</strong><br><span>${escapeHtml(user.email)}</span></td>
            <td><span class="account-badge ${user.accountStatus.toLowerCase()}">${escapeHtml(user.accountStatus)}</span></td>
            <td>${user.subjectCount}</td>
            <td>${escapeHtml(formatDateTime(user.createdAt))}</td>
            <td>${escapeHtml(formatDateTime(user.lastSessionAt))}</td>
            <td>${user.id === adminOverview.adminUserId
              ? '<span class="account-badge active">현재 관리자</span>'
              : `<div class="admin-user-actions">
                  <button class="button secondary" type="button" data-admin-status="ACTIVE" data-user-id="${user.id}">활성</button>
                  <button class="button secondary" type="button" data-admin-status="LOCKED" data-user-id="${user.id}">잠금</button>
                  <button class="button secondary" type="button" data-admin-status="WITHDRAWN" data-user-id="${user.id}">탈퇴</button>
                </div>`}</td>
          </tr>`).join("")
      : '<tr><td colspan="6">표시할 회원이 없습니다.</td></tr>';
  }

  async function openAdminPage() {
    if (!state.isAdmin) return;
    $("#adminButton").disabled = true;
    try {
      await loadAdminOverview();
      $("#dashboard").hidden = true;
      $("#adminPage").hidden = false;
      window.scrollTo({ top: 0, behavior: "smooth" });
    } catch (error) {
      toast(error.message);
    } finally {
      $("#adminButton").disabled = false;
    }
  }

  function toast(message) {
    clearTimeout(toastTimer);
    $("#toastMessage").textContent = message;
    $("#toast").classList.add("show");
    toastTimer = setTimeout(() => $("#toast").classList.remove("show"), 2800);
  }

  async function apiRequest(path, options = {}, allowRefresh = true) {
    const response = await fetch(`${apiConfig.baseUrl}${path}`, {
      credentials: "include",
      ...options,
      headers: { "Content-Type": "application/json", ...(options.headers || {}) }
    });
    const contentType = response.headers.get("content-type") || "";
    const payload = contentType.includes("application/json")
      ? await response.json()
      : { message: await response.text() };
    const refreshExcluded = ["/auth/login", "/auth/signup", "/auth/refresh"].includes(path);
    if (response.status === 401 && allowRefresh && !refreshExcluded) {
      const refreshed = await fetch(`${apiConfig.baseUrl}/auth/refresh`, {
        method: "POST", credentials: "include", headers: { "Content-Type": "application/json" }
      });
      if (refreshed.ok) return apiRequest(path, options, false);
    }
    if (!response.ok) {
      const error = new Error(payload.message || `요청에 실패했습니다. (HTTP ${response.status})`);
      error.status = response.status;
      throw error;
    }
    return payload;
  }

  function applyProfile(profile = []) {
    state.subjects = profile.map(item => item.subjectCode);
    state.subjectLevels = Object.fromEntries(profile.map(item => [item.subjectCode, item.levelLabel]));
  }

  function applyPlan(plan) {
    state.planId = plan?.id || null;
    state.planGenerated = Boolean(plan);
    state.planSteps = plan?.steps || [];
    state.tasks = [1, 2, 3].map(stepNo => {
      const step = state.planSteps.find(item => item.stepNo === stepNo);
      return step?.status === "COMPLETED";
    });
  }

  async function loadLearningState() {
    const [subjects, learning] = await Promise.all([
      apiRequest("/learning/subjects"),
      apiRequest("/learning/state")
    ]);
    subjectCatalog = subjects;
    applyProfile(learning.profile);
    applyPlan(learning.plan);
    state.stats = learning.stats || { solvedCount: 0, correctCount: 0, completedStepCount: 0 };
    state.quizFinished = Boolean(learning.diagnosis);
    state.quizCorrect = learning.diagnosis?.correctAnswers || 0;
    state.quizTotal = learning.diagnosis?.totalQuestions || 0;
    renderSubjectChoices();
  }

  async function restoreSession() {
    if (!apiConfig.enabled) {
      await detectAdmin();
      renderSubjectChoices();
      updateUI();
      return;
    }
    try {
      const payload = await apiRequest("/auth/me");
      state.authenticated = true;
      state.userName = payload.user.nickname;
      state.userEmail = payload.user.email;
      await loadLearningState();
      await detectAdmin();
    } catch (error) {
      if (error.status !== 401) toast(`서버 연결 확인이 필요합니다: ${error.message}`);
      state = { ...defaultState, tasks: [false, false, false] };
    }
    updateUI();
  }

  function renderSubjectChoices() {
    if (!subjectCatalog.length) {
      $("#subjectChoices").innerHTML = '<div class="subject-level-empty">활성 과목이 없습니다. DB의 subjects 데이터를 확인해 주세요.</div>';
      return;
    }
    $("#subjectChoices").innerHTML = subjectCatalog.map(subject => `
      <button class="choice${draftSubjects.includes(subject.code) ? " selected" : ""}" type="button" data-subject="${escapeHtml(subject.code)}">
        <strong>${escapeHtml(subject.name)}</strong><span>${escapeHtml(subjectDescriptions[subject.code] || "맞춤 학습")}</span>
      </button>`).join("");
  }

  function renderSubjectLevelSettings() {
    if (!draftSubjects.length) {
      $("#subjectLevelSettings").innerHTML = '<div class="subject-level-empty">과목을 선택하면 과목별 수준 설정이 나타납니다.</div>';
      return;
    }
    const descriptions = { "초급": "개념부터", "중급": "실습 중심", "고급": "설계 중심" };
    $("#subjectLevelSettings").innerHTML = draftSubjects.map(code => `
      <section class="subject-level-row" aria-label="${escapeHtml(subjectName(code))} 수준 설정">
        <div class="subject-level-head"><strong>${escapeHtml(subjectName(code))}</strong><span>${draftSubjectLevels[code] ? `${draftSubjectLevels[code]} 선택됨` : "수준을 선택해 주세요"}</span></div>
        <div class="subject-level-buttons">
          ${["초급", "중급", "고급"].map(level => `
            <button class="level-choice${draftSubjectLevels[code] === level ? " selected" : ""}" type="button" data-level-subject="${escapeHtml(code)}" data-level="${level}">
              <strong>${level}</strong><span>${descriptions[level]}</span>
            </button>`).join("")}
        </div>
      </section>`).join("");
  }

  function currentStep() {
    if (!state.authenticated) return 1;
    if (!hasCompleteProfile()) return 2;
    if (!state.planGenerated) return 3;
    if (!state.tasks.every(Boolean)) return 4;
    if (!state.quizFinished) return 5;
    return 6;
  }

  function planProgress() {
    return Math.round((state.tasks.filter(Boolean).length / 3) * 100);
  }

  function updateUI() {
    const completed = state.tasks.filter(Boolean).length;
    const progress = planProgress();
    const hasProfile = hasCompleteProfile();
    const tasksDone = state.tasks.every(Boolean);

    $("#loginButton").hidden = state.authenticated;
    $("#signupButton").hidden = state.authenticated;
    $("#logoutButton").hidden = !state.authenticated;
    $("#withdrawButton").hidden = !state.authenticated;
    $("#adminButton").hidden = !state.authenticated || !state.isAdmin;
    $("#resetDemo").hidden = apiConfig.enabled;
    $("#userChip").hidden = !state.authenticated;
    if (state.authenticated) {
      $("#userName").textContent = state.userName;
      $("#userInitial").textContent = state.userName.slice(0, 1);
    }

    $("#heroProgressText").textContent = `${progress}%`;
    $("#heroProgressBar").style.width = `${progress}%`;
    $("#heroProgress").setAttribute("aria-valuenow", String(progress));
    $("#completedCount").textContent = String(completed);
    $("#overallProgress").textContent = state.quizFinished ? "100" : String(progress);
    $("#overallCaption").textContent = state.quizFinished ? "오늘 학습이 대시보드에 반영됐어요." : completed ? "오늘 플랜을 진행하고 있어요." : "첫 학습을 시작하면 반영돼요.";

    if (state.quizFinished) {
      const accuracy = Math.round((state.quizCorrect / Math.max(state.quizTotal, 1)) * 100);
      $("#accuracyValue").textContent = String(accuracy);
      $("#accuracyUnit").textContent = "%";
      $("#accuracyCaption").textContent = `${state.quizTotal}문제 중 ${state.quizCorrect}문제 정답`;
    } else {
      $("#accuracyValue").textContent = "-";
      $("#accuracyUnit").textContent = "";
      $("#accuracyCaption").textContent = "문제 풀이 후 계산돼요.";
    }

    $("#profileLevel").textContent = hasProfile ? "과목별 맞춤 수준" : "미설정";
    $("#subjectTags").innerHTML = hasProfile
      ? state.subjects.map(code => `<span class="subject-tag">${escapeHtml(subjectName(code))} · ${escapeHtml(state.subjectLevels[code])}</span>`).join("")
      : '<span class="subject-tag">과목을 선택해 주세요</span>';
    $("#setupProgressText").textContent = hasProfile ? "100%" : state.authenticated ? "50%" : "0%";
    $("#setupProgressBar").style.width = hasProfile ? "100%" : state.authenticated ? "50%" : "0%";
    $("#generatePlan").disabled = !hasProfile;
    $("#generatePlan").textContent = state.planGenerated ? "플랜 다시 생성하기" : "오늘의 플랜 생성하기";

    $("#planEmpty").hidden = state.planGenerated;
    $("#planContent").hidden = !state.planGenerated;
    if (state.planGenerated) {
      const focus = state.subjects[0] || "KUBERNETES";
      const focusLevel = state.subjectLevels[focus] || "초급";
      $("#focusSubject").textContent = `${subjectName(focus)} · ${focusLevel}`;
      const fallbackTitles = [`${subjectName(focus)} 핵심 개념 익히기`, `${subjectName(focus)} 미니 실습 따라하기`, `${subjectName(focus)} 핵심 내용 복습`];
      const fallbackContents = ["개념 카드와 예제로 준비하기", "단계별 미니 실습 수행하기", "오늘 배운 내용 한 번 더 확인하기"];
      ["One", "Two", "Three"].forEach((suffix, index) => {
        const planStep = state.planSteps.find(item => item.stepNo === index + 1);
        $(`#task${suffix}Title`).textContent = planStep?.title || fallbackTitles[index];
        $(`#task${suffix}Content`).textContent = planStep?.content || fallbackContents[index];
      });
      $("#planBasis").textContent = `${profileLabel()} 설정 기준`;
      $("#planStatus").textContent = state.quizFinished ? "학습 완료" : completed ? "학습 중" : "플랜 준비됨";
      $("#planNote").textContent = state.quizFinished
        ? "오늘의 학습 결과가 대시보드에 반영되었습니다."
        : completed === 3 ? "학습을 모두 마쳤어요. 확인 문제에 도전해 보세요." : `3단계 중 ${completed}단계를 완료했습니다.`;
    } else {
      const focus = state.subjects[0];
      $("#focusSubject").textContent = hasProfile ? `${subjectName(focus)} · ${state.subjectLevels[focus]}` : "과목을 먼저 선택해 주세요";
      $("#planStatus").textContent = hasProfile ? "생성 대기" : "설정 전";
      $("#planNote").textContent = hasProfile ? "오늘의 플랜 생성 버튼을 눌러 학습을 시작하세요." : "회원가입 후 학습 프로필을 설정하면 맞춤 플랜을 생성할 수 있어요.";
    }

    $$(".timeline-item").forEach((item, index) => {
      const done = Boolean(state.tasks[index]);
      item.classList.toggle("completed", done);
      const button = $(".check-button", item);
      button.setAttribute("aria-pressed", String(done));
      button.setAttribute("aria-label", `${index + 1}단계 ${done ? "완료 취소" : "완료"}`);
    });
    $("#quizButton").disabled = !tasksDone;
    $("#quizGuide").textContent = state.quizFinished ? "풀이 결과가 대시보드에 반영되었습니다." : tasksDone ? "준비가 끝났어요. 5문제를 풀어 보세요." : "3단계를 모두 완료하면 문제 풀이가 열립니다.";

    if (!state.authenticated) $("#mainAction").textContent = "회원가입하고 시작하기";
    else if (!hasProfile) $("#mainAction").textContent = "과목·수준 설정하기";
    else if (!state.planGenerated) $("#mainAction").textContent = "오늘의 플랜 생성하기";
    else if (!tasksDone) $("#mainAction").textContent = "오늘 학습 이어하기";
    else if (!state.quizFinished) $("#mainAction").textContent = "확인 문제 풀기";
    else $("#mainAction").textContent = "오늘 학습 완료";
  }

  function setAuthMode(mode) {
    authMode = mode;
    clearAuthFields();
    $$('[data-auth-mode]').forEach(button => button.classList.toggle("active", button.dataset.authMode === mode));
    $("#nameField").hidden = mode === "login";
    $("#authTitle").textContent = mode === "signup" ? "NeuroPlan 회원가입" : "NeuroPlan 로그인";
    $("#authDescription").textContent = mode === "signup" ? "간단한 정보로 학습을 시작해 보세요." : "가입한 이메일과 비밀번호를 입력해 주세요.";
    $("#authHint").hidden = apiConfig.enabled || mode !== "login";
    $("#authPassword").autocomplete = mode === "signup" ? "new-password" : "current-password";
    $("#authSubmit").textContent = mode === "signup" ? "회원가입 완료" : "로그인";
  }

  function openProfile() {
    if (!state.authenticated) {
      setAuthMode("signup");
      openModal("authModal");
      toast("먼저 회원가입 또는 로그인이 필요해요.");
      return;
    }
    draftSubjects = [...state.subjects];
    draftSubjectLevels = { ...state.subjectLevels };
    renderSubjectChoices();
    renderSubjectLevelSettings();
    $("#profileMessage").textContent = "";
    openModal("profileModal");
  }

  async function generatePlan() {
    if (!hasCompleteProfile()) return;
    const button = $("#generatePlan");
    button.disabled = true;
    try {
      if (apiConfig.enabled) {
        applyPlan(await apiRequest("/learning/plans", { method: "POST" }));
      } else {
        state.planId = 1;
        state.planGenerated = true;
        state.planSteps = [];
        state.tasks = [false, false, false];
        saveState();
      }
      state.quizFinished = false;
      state.quizCorrect = 0;
      state.quizTotal = 0;
      updateUI();
      toast(`${subjectName(state.subjects[0])} 중심의 오늘 학습 플랜을 만들었어요.`);
      $("#todayPlanTitle").scrollIntoView({ behavior: "smooth", block: "center" });
    } catch (error) {
      toast(error.message);
    } finally {
      button.disabled = !hasCompleteProfile();
    }
  }

  async function startQuiz() {
    const code = state.subjects[0];
    if (!code) return;
    $("#quizButton").disabled = true;
    try {
      questions = apiConfig.enabled
        ? await apiRequest(`/learning/diagnosis/questions?subjectCode=${encodeURIComponent(code)}`)
        : fallbackQuestions;
      quizAnswers = [];
      quizIndex = 0;
      quizScore = 0;
      chosenAnswer = null;
      answerChecked = false;
      renderQuestion();
      openModal("quizModal");
    } catch (error) {
      toast(error.message);
    } finally {
      $("#quizButton").disabled = !state.tasks.every(Boolean);
    }
  }

  function renderQuestion() {
    const question = questions[quizIndex];
    $("#questionCounter").textContent = `${quizIndex + 1} / ${questions.length}`;
    $("#quizProgressBar").style.width = `${((quizIndex + 1) / questions.length) * 100}%`;
    $("#questionSubject").textContent = `${question.subjectName} · ${question.difficulty}`;
    $("#questionText").textContent = question.text;
    $("#quizSubjectText").textContent = `${subjectName(state.subjects[0])} 핵심 내용을 확인합니다.`;
    $("#answerList").innerHTML = question.options.map((option, index) => `
      <button class="answer" type="button" data-answer="${option.id}">
        <span class="answer-letter">${String.fromCharCode(65 + index)}</span><span>${escapeHtml(option.text)}</span>
      </button>`).join("");
    $("#explanation").hidden = true;
    $("#explanation").textContent = "";
    $("#nextQuestion").disabled = true;
    $("#nextQuestion").textContent = "정답 확인";
    chosenAnswer = null;
    answerChecked = false;
  }

  document.addEventListener("click", event => {
    const close = event.target.closest("[data-close]");
    if (close) closeModal(close.dataset.close);

    const authTab = event.target.closest("[data-auth-mode]");
    if (authTab) setAuthMode(authTab.dataset.authMode);

    const subject = event.target.closest("[data-subject]");
    if (subject) {
      const code = subject.dataset.subject;
      if (draftSubjects.includes(code)) {
        draftSubjects = draftSubjects.filter(item => item !== code);
        delete draftSubjectLevels[code];
      } else {
        if (draftSubjects.length >= 3) {
          $("#profileMessage").textContent = "학습 과목은 최대 3개까지 선택할 수 있습니다.";
          return;
        }
        draftSubjects.push(code);
      }
      $("#profileMessage").textContent = "";
      renderSubjectChoices();
      renderSubjectLevelSettings();
    }

    const level = event.target.closest("[data-level-subject]");
    if (level) {
      draftSubjectLevels[level.dataset.levelSubject] = level.dataset.level;
      renderSubjectLevelSettings();
    }

    const answer = event.target.closest("[data-answer]");
    if (answer && !answerChecked) {
      chosenAnswer = Number(answer.dataset.answer);
      $$('[data-answer]').forEach(button => button.classList.toggle("selected", Number(button.dataset.answer) === chosenAnswer));
      $("#nextQuestion").disabled = false;
    }
  });

  $("#authForm").addEventListener("submit", async event => {
    event.preventDefault();
    const name = $("#authName").value.trim();
    const email = $("#authEmail").value.trim();
    const password = $("#authPassword").value;
    if ((authMode === "signup" && !name) || !email.includes("@") || password.length < 8) {
      $("#authMessage").textContent = "이름, 올바른 이메일, 8자 이상의 비밀번호를 확인해 주세요.";
      return;
    }
    $("#authSubmit").disabled = true;
    $("#authMessage").textContent = apiConfig.enabled ? "서버에서 확인하고 있어요..." : "";
    try {
      if (apiConfig.enabled) {
        const payload = await apiRequest(authMode === "signup" ? "/auth/signup" : "/auth/login", {
          method: "POST",
          body: JSON.stringify(authMode === "signup" ? { nickname: name, email, password } : { email, password })
        });
        state.userName = payload.user.nickname;
        state.userEmail = payload.user.email;
      } else {
        state.userName = authMode === "signup" ? name : (state.userName || email.split("@")[0]);
        state.userEmail = email.toLowerCase();
      }
      state.authenticated = true;
      if (apiConfig.enabled) await loadLearningState();
      else saveState();
      await detectAdmin();
      closeModal("authModal");
      updateUI();
      toast(authMode === "signup" ? `${state.userName}님, 가입을 환영합니다!` : `${state.userName}님, 다시 만나 반가워요.`);
      if (!hasCompleteProfile()) setTimeout(openProfile, 260);
    } catch (error) {
      $("#authMessage").textContent = error.message;
    } finally {
      $("#authSubmit").disabled = false;
    }
  });

  $("#saveProfile").addEventListener("click", async () => {
    const missingLevels = draftSubjects.filter(code => !draftSubjectLevels[code]);
    if (!draftSubjects.length || draftSubjects.length > 3 || missingLevels.length) {
      $("#profileMessage").textContent = !draftSubjects.length
        ? "학습 과목을 최소 한 개 선택해 주세요."
        : draftSubjects.length > 3 ? "학습 과목은 최대 3개까지 선택할 수 있습니다."
          : `${missingLevels.map(subjectName).join(", ")} 과목의 수준을 선택해 주세요.`;
      return;
    }
    const button = $("#saveProfile");
    button.disabled = true;
    try {
      if (apiConfig.enabled) {
        const profile = await apiRequest("/learning/profile", {
          method: "PUT",
          body: JSON.stringify({ subjects: draftSubjects.map(code => ({ code, learningLevel: levelToApi[draftSubjectLevels[code]] })) })
        });
        applyProfile(profile);
      } else {
        state.subjects = [...draftSubjects];
        state.subjectLevels = Object.fromEntries(state.subjects.map(code => [code, draftSubjectLevels[code]]));
      }
      applyPlan(null);
      state.quizFinished = false;
      state.quizCorrect = 0;
      state.quizTotal = 0;
      saveState();
      closeModal("profileModal");
      updateUI();
      toast(`${profileLabel()} 프로필을 저장했어요.`);
    } catch (error) {
      $("#profileMessage").textContent = error.message;
    } finally {
      button.disabled = false;
    }
  });

  $("#generatePlan").addEventListener("click", generatePlan);
  $("#quizButton").addEventListener("click", startQuiz);
  $("#editProfile").addEventListener("click", openProfile);
  $("#profileAction").addEventListener("click", openProfile);
  $("#loginButton").addEventListener("click", () => { setAuthMode("login"); openModal("authModal"); });
  $("#signupButton").addEventListener("click", () => { setAuthMode("signup"); openModal("authModal"); });
  $("#adminButton").addEventListener("click", openAdminPage);
  $("#adminBackButton").addEventListener("click", showLearningPage);
  $("#adminRefresh").addEventListener("click", async () => {
    try {
      await loadAdminOverview();
      toast("관리자 데이터를 새로고침했습니다.");
    } catch (error) {
      toast(error.message);
    }
  });
  $("#homeBrand").addEventListener("click", event => {
    event.preventDefault();
    showLearningPage();
  });
  $("#withdrawButton").addEventListener("click", () => {
    $("#withdrawForm").reset();
    $("#withdrawMessage").textContent = "";
    openModal("withdrawModal");
  });

  $("#withdrawForm").addEventListener("submit", async event => {
    event.preventDefault();
    const password = $("#withdrawPassword").value;
    if (password.length < 8) {
      $("#withdrawMessage").textContent = "현재 비밀번호를 8자 이상 입력해 주세요.";
      return;
    }
    if (!confirm("정말 회원 탈퇴하시겠습니까? 이 계정은 다시 로그인할 수 없습니다.")) return;
    $("#withdrawSubmit").disabled = true;
    try {
      if (apiConfig.enabled) {
        await apiRequest("/auth/withdraw", { method: "POST", body: JSON.stringify({ password }) });
      }
      state = { ...defaultState, tasks: [false, false, false] };
      localStorage.removeItem(storageKey);
      adminOverview = null;
      closeModal("withdrawModal");
      showLearningPage();
      updateUI();
      toast("회원 탈퇴가 완료되었습니다.");
    } catch (error) {
      $("#withdrawMessage").textContent = error.message;
    } finally {
      $("#withdrawSubmit").disabled = false;
    }
  });

  $("#logoutButton").addEventListener("click", async () => {
    try {
      if (apiConfig.enabled) await apiRequest("/auth/logout", { method: "POST" });
    } catch (error) {
      toast(`로그아웃 요청 확인이 필요합니다: ${error.message}`);
    } finally {
      state = { ...defaultState, tasks: [false, false, false] };
      adminOverview = null;
      localStorage.removeItem(storageKey);
      clearAuthFields();
      showLearningPage();
      updateUI();
      toast("로그아웃했습니다.");
    }
  });

  $("#mainAction").addEventListener("click", () => {
    const step = currentStep();
    if (step === 1) { setAuthMode("signup"); openModal("authModal"); }
    else if (step === 2) openProfile();
    else if (step === 3) generatePlan();
    else if (step === 4) $("#todayPlanTitle").scrollIntoView({ behavior: "smooth", block: "center" });
    else if (step === 5) startQuiz();
    else toast("오늘 학습을 모두 완료했어요. 수고하셨습니다!");
  });

  $$(".timeline-item").forEach((item, index) => {
    $(".check-button", item).addEventListener("click", async () => {
      const completed = !state.tasks[index];
      const button = $(".check-button", item);
      button.disabled = true;
      try {
        if (apiConfig.enabled) {
          applyPlan(await apiRequest(`/learning/plans/${state.planId}/steps/${index + 1}`, {
            method: "PATCH", body: JSON.stringify({ completed })
          }));
          state.stats.completedStepCount = state.tasks.filter(Boolean).length;
        } else {
          state.tasks[index] = completed;
          saveState();
        }
        updateUI();
        toast(completed ? `${index + 1}단계 학습을 완료했어요.` : `${index + 1}단계 완료를 취소했어요.`);
      } catch (error) {
        toast(error.message);
      } finally {
        button.disabled = false;
      }
    });
  });

  $("#nextQuestion").addEventListener("click", async () => {
    const question = questions[quizIndex];
    if (!answerChecked) {
      $("#nextQuestion").disabled = true;
      try {
        const checked = apiConfig.enabled
          ? await apiRequest("/learning/diagnosis/check", {
              method: "POST", body: JSON.stringify({ questionId: question.id, selectedOptionId: chosenAnswer })
            })
          : { correct: chosenAnswer === question.correctOptionId, correctOptionId: question.correctOptionId, explanation: question.explanation };
        answerChecked = true;
        if (checked.correct) quizScore += 1;
        quizAnswers.push({ questionId: question.id, selectedOptionId: chosenAnswer });
        $$('[data-answer]').forEach(button => {
          const optionId = Number(button.dataset.answer);
          button.classList.remove("selected");
          if (optionId === checked.correctOptionId) button.classList.add("correct");
          else if (optionId === chosenAnswer) button.classList.add("wrong");
        });
        $("#explanation").textContent = `${checked.correct ? "정답입니다. " : "아쉽지만 오답입니다. "}${checked.explanation}`;
        $("#explanation").hidden = false;
        $("#nextQuestion").textContent = quizIndex === questions.length - 1 ? "결과 확인" : "다음 문제";
      } catch (error) {
        toast(error.message);
      } finally {
        $("#nextQuestion").disabled = false;
      }
      return;
    }

    if (quizIndex < questions.length - 1) {
      quizIndex += 1;
      renderQuestion();
      return;
    }

    $("#nextQuestion").disabled = true;
    try {
      if (apiConfig.enabled) {
        const result = await apiRequest("/learning/diagnosis/attempts", {
          method: "POST", body: JSON.stringify({ subjectCode: state.subjects[0], answers: quizAnswers })
        });
        state.quizCorrect = result.correctAnswers;
        state.quizTotal = result.totalQuestions;
        await loadLearningState();
      } else {
        state.quizCorrect = quizScore;
        state.quizTotal = questions.length;
        state.quizFinished = true;
        state.stats.solvedCount += questions.length;
        state.stats.correctCount += quizScore;
        saveState();
      }
      closeModal("quizModal");
      updateUI();
      toast(`정답률 ${Math.round((state.quizCorrect / Math.max(state.quizTotal, 1)) * 100)}%가 대시보드에 반영됐어요.`);
      $("#dashboard").scrollIntoView({ behavior: "smooth", block: "start" });
    } catch (error) {
      toast(error.message);
    } finally {
      $("#nextQuestion").disabled = false;
    }
  });

  $("#resetDemo").addEventListener("click", () => {
    if (!confirm("회원가입부터 다시 테스트할 수 있도록 데모 데이터를 초기화할까요?")) return;
    state = { ...defaultState, tasks: [false, false, false] };
    localStorage.removeItem(storageKey);
    clearAuthFields();
    updateUI();
    toast("데모 데이터를 초기화했습니다.");
  });

  // 인증/프로필/퀴즈 모달은 실수로 닫히지 않도록 배경 클릭을 무시합니다.
  // 명시적인 X 버튼과 Escape 키만 닫기 동작으로 사용합니다.
  document.addEventListener("keydown", event => {
    if (event.key !== "Escape") return;
    const openBackdrop = $$(".modal-backdrop").find(backdrop => !backdrop.hidden);
    if (openBackdrop) closeModal(openBackdrop.id);
  });

  document.addEventListener("click", async event => {
    const action = event.target.closest("[data-admin-status]");
    if (!action || !state.isAdmin || !apiConfig.enabled) return;
    const status = action.dataset.adminStatus;
    const userId = Number(action.dataset.userId);
    if (!confirm(`회원 #${userId} 상태를 ${status}(으)로 변경할까요?`)) return;
    action.disabled = true;
    try {
      await apiRequest(`/admin/users/${userId}/status`, {
        method: "PATCH", body: JSON.stringify({ status })
      });
      await loadAdminOverview();
      toast("회원 상태를 변경했습니다.");
    } catch (error) {
      toast(error.message);
    } finally {
      action.disabled = false;
    }
  });

  renderSubjectChoices();
  updateUI();
  restoreSession();
})();
