#include <cerrno>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <climits>
#include <pwd.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cctype>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <regex>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include <dirent.h>

#include "json.hpp"

namespace fs = std::filesystem;
using json = nlohmann::json;

static std::string toLower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(),
                   [](unsigned char c){ return std::tolower(c); });
    return s;
}

static bool isCpuThermalType(const std::string& type) {
    std::string t = toLower(type);
    return (t.find("cpu") != std::string::npos) ||
           (t.find("soc") != std::string::npos) ||
           (t.find("ap")  != std::string::npos) ||
           (t.find("cluster") != std::string::npos);
}

int getCpuTemperatureCelsius() {
    const std::string basePath = "/sys/class/thermal/";
    DIR* dir = opendir(basePath.c_str());
    if (!dir) return -1;

    struct dirent* entry;
    int maxTemp = -1;

    while ((entry = readdir(dir)) != nullptr) {
        std::string name = entry->d_name;
        if (name.find("thermal_zone") == std::string::npos) continue;

        std::string zonePath = basePath + name;
        std::ifstream typeFile(zonePath + "/type");
        if (!typeFile.is_open()) continue;

        std::string type;
        std::getline(typeFile, type);
        typeFile.close();

        if (!isCpuThermalType(type)) continue;

        std::ifstream tempFile(zonePath + "/temp");
        if (!tempFile.is_open()) continue;

        long raw = 0;
        tempFile >> raw;
        tempFile.close();

        if (raw <= 0) continue;

        int tempC = (raw > 1000) ? static_cast<int>(raw / 1000) : static_cast<int>(raw);
        if (tempC >= 5 && tempC <= 100) {
            maxTemp = std::max(maxTemp, tempC);
        }
    }

    closedir(dir);
    return maxTemp;
}

// One uevent read carries every battery field; apps cannot open
// /sys/class/power_supply (SELinux) so they ask the daemon instead
static void getBatteryInfo(json &out) {
    std::ifstream uevent("/sys/class/power_supply/battery/uevent");
    std::string line;
    while (std::getline(uevent, line)) {
        const size_t eq = line.find('=');
        if (eq == std::string::npos) continue;
        const std::string key = line.substr(0, eq);
        const std::string val = line.substr(eq + 1);
        try {
            if (key == "POWER_SUPPLY_STATUS") out["status"] = val;
            else if (key == "POWER_SUPPLY_HEALTH") out["health"] = val;
            else if (key == "POWER_SUPPLY_CAPACITY") out["capacity"] = std::stoi(val);
            else if (key == "POWER_SUPPLY_TEMP") out["temp"] = std::stoi(val);
            else if (key == "POWER_SUPPLY_VOLTAGE_NOW") out["voltageUV"] = std::stoll(val);
            else if (key == "POWER_SUPPLY_CURRENT_NOW") out["currentUA"] = std::stoll(val);
            else if (key == "POWER_SUPPLY_CYCLE_COUNT") out["cycles"] = std::stoi(val);
            else if (key == "POWER_SUPPLY_CHARGE_TYPE") out["chargeType"] = val;
        } catch (...) {}
    }

    // USB type lives on the usb supply; Qualcomm pmic_glink exposes only
    // POWER_SUPPLY_TYPE (=USB_PD etc.) while others carry POWER_SUPPLY_USB_TYPE
    {
        std::ifstream usbUevent("/sys/class/power_supply/usb/uevent");
        std::string uline;
        while (std::getline(usbUevent, uline)) {
            if (uline.compare(0, 22, "POWER_SUPPLY_USB_TYPE=") == 0) {
                out["usbType"] = uline.substr(22);
                break;
            }
        }
        if (!out.contains("usbType")) {
            usbUevent.clear();
            usbUevent.seekg(0);
            while (std::getline(usbUevent, uline)) {
                if (uline.compare(0, 18, "POWER_SUPPLY_TYPE=") == 0) {
                    const std::string t = uline.substr(18);
                    if (t != "Battery" && t != "Unknown" && t != "N/A") out["usbType"] = t;
                    break;
                }
            }
        }
    }
}

static std::regex pid_regex("\\d+");

std::vector<int> listPids() {
    std::vector<int> pids;
    pids.reserve(256);
    for (const auto &entry : fs::directory_iterator("/proc")) {
        try {
            if (entry.is_directory()) {
                std::string name = entry.path().filename();
                if (std::regex_match(name, pid_regex)) {
                    pids.push_back(std::stoi(name));
                }
            }
        } catch (...) {}
    }
    return pids;
}

static volatile sig_atomic_t keep_running = 1;

void handle_sigint(int) {
    keep_running = 0;
}

std::string now_str() {
    time_t t = time(nullptr);
    struct tm tm{};
    localtime_r(&t, &tm);
    char buf[64];
    strftime(buf, sizeof(buf), "%Y-%m-%d %H:%M:%S", &tm);
    return {buf};
}

void log_line(const std::string &line) {
    std::string msg = "[" + now_str() + "] " + line + "\n";
    write(STDERR_FILENO, msg.c_str(), msg.size());
}

bool send_msg(const std::string &msg) {
    std::string data = msg + "\n";
    size_t total = 0;
    while (total < data.size()) {
        ssize_t written = write(STDOUT_FILENO, data.data() + total, data.size() - total);
        if (written <= 0) return false;
        total += written;
    }
    return true;
}

bool send_json(const json &j) {
    return send_msg(j.dump());
}

struct CpuStat {
    long user, nice, system, idle, iowait, irq, softirq, steal;
    long total() const { return user + nice + system + idle + iowait + irq + softirq + steal; }
    long active() const { return total() - idle; }
};

CpuStat readCpuStat() {
    std::ifstream file("/proc/stat");
    if (!file.is_open()) return {0,0,0,0,0,0,0,0};
    std::string line;
    std::getline(file, line);
    if (line.rfind("cpu ", 0) == 0) {
        std::istringstream iss(line);
        std::string cpuLabel;
        long v[8] = {0};
        iss >> cpuLabel;
        for (int i = 0; i < 8; ++i) if (!(iss >> v[i])) break;
        return {v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]};
    }
    return {0,0,0,0,0,0,0,0};
}

int calculateCpuUsage() {
    CpuStat prev = readCpuStat();
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    CpuStat curr = readCpuStat();
    uint64_t totalDiff = curr.total() - prev.total();
    uint64_t activeDiff = curr.active() - prev.active();
    if (totalDiff == 0) return 0;
    double usage = (double)activeDiff / (double)totalDiff * 100.0;
    return std::clamp((int)usage, 0, 100);
}

// Parses files that expose GPU busy time. Supports several formats:
//   - single percentage value ("50")
//   - busy/total pairs separated by whitespace, '@' or '/' ("1234 5678", "1234@5678")
// Returns usage 0..100, or -1 when the file is unreadable/unsupported.
static int readBusyPercentageFile(const std::string& path) {
    std::ifstream file(path);
    std::string line;
    if (!std::getline(file, line)) return -1;

    for (char& c : line) {
        if (!std::isdigit(static_cast<unsigned char>(c))) c = ' ';
    }

    std::istringstream iss(line);
    long a = -1, b = -1;
    if (!(iss >> a)) return -1;
    if (iss >> b) {
        if (b > 0) return std::clamp((int)(a * 100 / b), 0, 100);
    } else if (a >= 0 && a <= 100) {
        return (int)a;
    }
    return -1;
}

// Scans /sys/class/devfreq for a GPU-related node exposing a "load" file.
// Works on many SoCs (Exynos, MediaTek, Kirin, etc.).
static int readDevfreqGpuLoad() {
    const fs::path base("/sys/class/devfreq");
    std::error_code ec;
    if (!fs::is_directory(base, ec)) return -1;

    for (const auto& entry : fs::directory_iterator(base, ec)) {
        if (ec) break;
        std::string name = toLower(entry.path().filename().string());
        if (name.find("gpu") == std::string::npos &&
            name.find("kgsl") == std::string::npos &&
            name.find("mali") == std::string::npos &&
            name.find("midgard") == std::string::npos &&
            name.find("panfrost") == std::string::npos) {
            continue;
        }
        int load = readBusyPercentageFile((entry.path() / "load").string());
        if (load >= 0) return load;
    }
    return -1;
}

int calculateGpuUsage() {
    // Qualcomm Adreno (KGSL)
    int usage = readBusyPercentageFile("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage");
    if (usage >= 0) return usage;
    usage = readBusyPercentageFile("/sys/class/kgsl/kgsl-3d0/gpubusy");
    if (usage >= 0) return usage;
    usage = readBusyPercentageFile("/sys/class/kgsl/kgsl-3d0/gpu_busy");
    if (usage >= 0) return usage;

    // ARM Mali
    usage = readBusyPercentageFile("/sys/class/misc/mali0/device/utilization");
    if (usage >= 0) return usage;
    usage = readBusyPercentageFile("/sys/class/misc/mali0/device/gpu_busy_percentage");
    if (usage >= 0) return usage;
    usage = readBusyPercentageFile("/proc/mali/utilization");
    if (usage >= 0) return usage;

    // Samsung Exynos / generic
    usage = readBusyPercentageFile("/sys/kernel/gpu/gpu_busy");
    if (usage >= 0) return usage;
    usage = readBusyPercentageFile("/sys/kernel/gpu/gpu_busy_percentage");
    if (usage >= 0) return usage;

    // Root-only debugfs paths
    usage = readBusyPercentageFile("/sys/kernel/debug/kgsl/kgsl-3d0/gpubusy");
    if (usage >= 0) return usage;
    usage = readBusyPercentageFile("/d/kgsl/kgsl-3d0/gpubusy");
    if (usage >= 0) return usage;

    // Generic devfreq load
    usage = readDevfreqGpuLoad();
    if (usage >= 0) return usage;

    return -1;
}

bool killProcess(int pid) {
    if (kill(pid, SIGKILL) == 0) return true;
    std::cerr << "Failed to kill process " << pid << ": " << strerror(errno) << std::endl;
    return false;
}

bool killProcessGroup(pid_t pgid, int signal = SIGKILL) {
    return kill(-pgid, signal) == 0;
}

struct Proc {
    int pid;
    std::string name;
    int nice;
    int uid;
    long long cpuJiffies;
    float cpuUsage;
    int parentPid;
    bool isForeground;
    long memoryUsageKb;
    std::string cmdLine;
    std::string state;
    int threads;
    long startTime;
    float elapsedTime;
    long residentSetSizeKb;
    long virtualMemoryKb;
    std::string cgroup;
    std::string executablePath;
    long swapUsageKb;
    bool frozen;
};

long getSystemUptime() {
    std::ifstream uptime("/proc/uptime");
    double uptimeSeconds = 0.0;
    if (uptime.is_open()) uptime >> uptimeSeconds;
    return static_cast<long>(uptimeSeconds * sysconf(_SC_CLK_TCK));
}

struct ProcCpuSample {
    unsigned long long jiffies;
    double wallSeconds;
};

static std::unordered_map<int, ProcCpuSample> g_prevCpuSamples;
static std::unordered_map<int, float> g_lastSubtreeCpuPct;

static bool readProcCpuJiffies(int pid, unsigned long long &out) {
    std::ifstream statFile("/proc/" + std::to_string(pid) + "/stat");
    if (!statFile.is_open()) return false;
    std::string line;
    if (!std::getline(statFile, line)) return false;
    size_t lastParen = line.rfind(')');
    if (lastParen == std::string::npos) return false;
    std::istringstream iss(line.substr(lastParen + 2));
    std::string dummy;
    for (int i = 0; i < 11; ++i) iss >> dummy;
    unsigned long long utime = 0, stime = 0;
    if (!(iss >> utime >> stime)) return false;
    out = utime + stime;
    return true;
}

static float measureProcessCpuInstant(int pid, int windowMs) {
    unsigned long long start = 0, end = 0;
    if (!readProcCpuJiffies(pid, start)) return 0.0f;
    std::this_thread::sleep_for(std::chrono::milliseconds(windowMs));
    if (!readProcCpuJiffies(pid, end)) return 0.0f;
    if (end <= start) return 0.0f;
    const long clkTck = sysconf(_SC_CLK_TCK);
    if (clkTck <= 0) return 0.0f;
    const double seconds = windowMs / 1000.0;
    return static_cast<float>((double)(end - start) / seconds / clkTck * 100.0);
}

// Instantaneous per-refresh CPU rate; every process row carries the sum over its
// whole descendant tree so parents (e.g. a shell running gradle) show the CPU
// their children burn
static void computeInstantCpu(std::vector<Proc>& procs) {
    using clock = std::chrono::steady_clock;
    const double nowWall = std::chrono::duration<double>(clock::now().time_since_epoch()).count();
    const long clkTck = sysconf(_SC_CLK_TCK);

    std::unordered_map<int, ProcCpuSample> current;
    std::unordered_map<int, float> ownPct;
    current.reserve(procs.size() * 2);
    ownPct.reserve(procs.size() * 2);

    for (const auto& p : procs) {
        current[p.pid] = {static_cast<unsigned long long>(p.cpuJiffies), nowWall};
        float pct = 0.0f;
        auto prev = g_prevCpuSamples.find(p.pid);
        if (prev != g_prevCpuSamples.end()) {
            const double dt = nowWall - prev->second.wallSeconds;
            if (dt >= 0.05 && clkTck > 0 && p.cpuJiffies >= prev->second.jiffies) {
                pct = static_cast<float>((double)(p.cpuJiffies - prev->second.jiffies) / dt / clkTck * 100.0);
            }
        }
        ownPct[p.pid] = pct;
    }
    g_prevCpuSamples = std::move(current);

    std::unordered_map<int, float> totals(ownPct);
    std::unordered_map<int, int> parentOf;
    parentOf.reserve(procs.size() * 2);
    for (const auto& p : procs) parentOf[p.pid] = p.parentPid;

    for (const auto& p : procs) {
        const float own = ownPct[p.pid];
        if (own <= 0.0f) continue;
        int cur = p.parentPid;
        int hops = 0;
        while (cur > 0 && hops < 256) {
            auto it = totals.find(cur);
            if (it == totals.end()) break;
            it->second += own;
            int next = 0;
            auto po = parentOf.find(cur);
            if (po != parentOf.end()) next = po->second;
            if (next <= 0 || next == cur) break;
            cur = next;
            ++hops;
        }
    }

    g_lastSubtreeCpuPct = totals;
    for (auto& p : procs) {
        auto it = totals.find(p.pid);
        if (it != totals.end()) p.cpuUsage = it->second;
    }
}

// A freshly started daemon has no jiffies baseline, so its first PROCESS_LIST
// reports 0% for every row and the client's CPU sort degenerates to pid order
// until the next refresh. Snapshot a cheap utime+stime baseline here and block
// briefly so THIS request already carries a real short-window rate.
static void seedCpuSamples() {
    const double wall = std::chrono::duration<double>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    std::unordered_map<int, ProcCpuSample> seed;
    DIR *procDir = opendir("/proc");
    if (!procDir) return;
    while (dirent *entry = readdir(procDir)) {
        const int pid = atoi(entry->d_name);
        if (pid <= 0) continue;
        unsigned long long jiffies = 0;
        if (readProcCpuJiffies(pid, jiffies)) seed[pid] = {jiffies, wall};
    }
    closedir(procDir);
    std::this_thread::sleep_for(std::chrono::milliseconds(300));
    g_prevCpuSamples = std::move(seed);
}

bool isForegroundProcess(int pid) {
    std::string oomPath = "/proc/" + std::to_string(pid) + "/oom_score_adj";
    std::ifstream oomFile(oomPath);
    if (!oomFile.is_open()) return false;
    int oomScore = 0;
    oomFile >> oomScore;
    return oomScore <= 100;
}

std::string getCgroup(int pid) {
    std::string cgroupPath = "/proc/" + std::to_string(pid) + "/cgroup";
    std::ifstream cgroupFile(cgroupPath);
    if (!cgroupFile.is_open()) return "";
    std::string line;
    if (std::getline(cgroupFile, line)) {
        size_t colonPos = line.find_last_of(':');
        if (colonPos != std::string::npos) return line.substr(colonPos + 1);
    }
    return line;
}

// Android 12+ parks cached apps in the cgroup v2 freezer; frozen processes have
// their anon pages migrated to zram/swap over time. The unified-hierarchy entry
// is the "0::<path>" line of /proc/<pid>/cgroup — earlier lines are v1 controllers
static bool isProcessFrozen(int pid) {
    std::ifstream cgroupFile("/proc/" + std::to_string(pid) + "/cgroup");
    if (!cgroupFile.is_open()) return false;
    std::string line;
    while (std::getline(cgroupFile, line)) {
        if (line.rfind("0::", 0) != 0) continue;
        const std::string path = line.substr(3);
        if (path.empty() || path[0] != '/') return false;
        std::ifstream fr("/sys/fs/cgroup" + path + "/cgroup.freeze");
        std::string value;
        if (!fr.is_open() || !(fr >> value)) return false;
        return value == "1";
    }
    return false;
}

std::string getExecutablePath(int pid) {
    std::string exePath = "/proc/" + std::to_string(pid) + "/exe";
    char path[PATH_MAX];
    ssize_t len = readlink(exePath.c_str(), path, sizeof(path) - 1);
    if (len != -1) { path[len] = '\0'; return std::string(path); }
    return "";
}

Proc readProc(int pid) {
    Proc p{}; p.pid = pid;
    std::string procPath = "/proc/" + std::to_string(pid);
    std::ifstream commFile(procPath + "/comm");
    if (commFile.is_open()) std::getline(commFile, p.name);
    std::ifstream cmdFile(procPath + "/cmdline", std::ios::binary);
    if (cmdFile.is_open()) std::getline(cmdFile, p.cmdLine, '\0');
    std::ifstream statFile(procPath + "/stat");
    if (statFile.is_open()) {
        std::string line; std::getline(statFile, line);
        size_t lastParen = line.rfind(')');
        if (lastParen != std::string::npos) {
            std::istringstream iss(line.substr(lastParen + 2));
            std::string dummy;
            for (int i = 0; i < 6; ++i) iss >> dummy;
            for (int i = 0; i < 5; ++i) iss >> dummy;
            long long utime = 0, stime = 0;
            iss >> utime;
            iss >> stime;
            p.cpuJiffies = utime + stime;
            for (int i = 0; i < 3; ++i) iss >> dummy;
            iss >> p.nice;
            iss >> dummy >> dummy;
            iss >> p.startTime;
        }
    }
    long uptime = getSystemUptime();
    p.elapsedTime = static_cast<float>(uptime - p.startTime) / sysconf(_SC_CLK_TCK);
    std::ifstream statusFile(procPath + "/status");
    std::string line;
    int fieldsFound = 0;
    while (fieldsFound < 7 && std::getline(statusFile, line)) {
        if (line.compare(0, 4, "Uid:") == 0) { p.uid = std::stoi(line.substr(5)); fieldsFound++; }
        else if (line.compare(0, 5, "PPid:") == 0) { p.parentPid = std::stoi(line.substr(6)); fieldsFound++; }
        else if (line.compare(0, 6, "VmRSS:") == 0) { p.residentSetSizeKb = std::stol(line.substr(7)); p.memoryUsageKb = p.residentSetSizeKb; fieldsFound++; }
        else if (line.compare(0, 7, "VmSwap:") == 0) { p.swapUsageKb = std::stol(line.substr(8)); fieldsFound++; }
        else if (line.compare(0, 7, "VmSize:") == 0) { p.virtualMemoryKb = std::stol(line.substr(8)); fieldsFound++; }
        else if (line.compare(0, 8, "Threads:") == 0) { p.threads = std::stoi(line.substr(9)); fieldsFound++; }
        else if (line.compare(0, 6, "State:") == 0) { p.state = line.substr(7); fieldsFound++; }
    }
    p.isForeground = isForegroundProcess(pid);
    p.cgroup = getCgroup(pid);
    p.frozen = isProcessFrozen(pid);
    p.executablePath = getExecutablePath(pid);
    return p;
}

json procToJson(const Proc &p) {
    return {
        {"pid", p.pid}, {"name", p.name}, {"nice", p.nice}, {"uid", p.uid},
        {"cpuUsage", p.cpuUsage}, {"parentPid", p.parentPid}, {"isForeground", p.isForeground},
        {"memoryUsageKb", p.memoryUsageKb}, {"cmdLine", p.cmdLine}, {"state", p.state},
        {"threads", p.threads}, {"startTime", p.startTime}, {"elapsedTime", p.elapsedTime},
        {"residentSetSizeKb", p.residentSetSizeKb}, {"virtualMemoryKb", p.virtualMemoryKb},
        {"cgroup", p.cgroup}, {"executablePath", p.executablePath},
        {"swapKb", p.swapUsageKb}, {"frozen", p.frozen}
    };
}

std::vector<Proc> collectProcs() {
    std::vector<Proc> procs;
    std::vector<int> pids = listPids();
    procs.reserve(pids.size());
    for (int pid : pids) { try { procs.push_back(readProc(pid)); } catch (...) {} }
    return procs;
}

void getSwapUsage(long &used, long &total) {
    used = 0; total = 0;
    std::ifstream meminfo("/proc/meminfo");
    if (!meminfo.is_open()) return;
    long totalKB = 0, freeKB = 0;
    std::string line;
    while (std::getline(meminfo, line)) {
        if (line.compare(0, 10, "SwapTotal:") == 0) totalKB = std::stol(line.substr(10));
        else if (line.compare(0, 9, "SwapFree:") == 0) freeKB = std::stol(line.substr(9));
    }
    used = (totalKB - freeKB) * 1024;
    total = totalKB * 1024;
}

struct NetStat {
    unsigned long long rxBytes;
    unsigned long long txBytes;
};

struct NetInterfaceInfo {
    std::string name;
    unsigned long long totalBytes;
};

std::vector<NetInterfaceInfo> listNetInterfaces() {
    std::vector<NetInterfaceInfo> interfaces;
    std::ifstream netdev("/proc/net/dev");
    std::string line;
    if (!netdev.is_open()) return interfaces;

    std::getline(netdev, line);
    std::getline(netdev, line);
    while (std::getline(netdev, line)) {
        size_t colon = line.find(':');
        if (colon != std::string::npos) {
            std::string name = line.substr(0, colon);
            name.erase(0, name.find_first_not_of(' '));
            if (name == "lo") continue;

            std::istringstream iss(line.substr(colon + 1));
            unsigned long long rxBytes, txBytes, dummy;
            iss >> rxBytes;
            for(int i=0; i<7; ++i) iss >> dummy;
            iss >> txBytes;

            interfaces.push_back({name, rxBytes + txBytes});
        }
    }
    return interfaces;
}

NetStat getNetStat(const std::string& iface) {
    std::ifstream netdev("/proc/net/dev");
    std::string line;
    while (std::getline(netdev, line)) {
        if (line.find(iface + ":") != std::string::npos) {
            std::istringstream iss(line.substr(line.find(':') + 1));
            unsigned long long rxBytes, dummy;
            unsigned long long txBytes;
            iss >> rxBytes;
            for(int i=0; i<7; ++i) iss >> dummy;
            iss >> txBytes;
            return {rxBytes, txBytes};
        }
    }
    return {0, 0};
}

struct NetStatSnapshot {
    unsigned long long rxBytes;
    unsigned long long txBytes;
    std::chrono::steady_clock::time_point timestamp;
};

static std::unordered_map<std::string, NetStatSnapshot> netStatCache;

// PackageManager queries of the app cannot see other Android users, so the daemon
// enumerates every running user's packages itself and maps full uids to package
// names; the app uses this as the authoritative resolution source
struct PkgEntry {
    std::string name;
    int userId;
};

static std::unordered_map<int, PkgEntry> g_uidToPkg;
static std::chrono::steady_clock::time_point g_pkgCacheRefreshedAt{};
static constexpr double PKG_CACHE_TTL_SECONDS = 60.0;

static std::string execCommand(const std::string &cmd) {
    std::string out;
    FILE *fp = popen(cmd.c_str(), "r");
    if (!fp) return out;
    char buf[512];
    while (fgets(buf, sizeof(buf), fp)) out += buf;
    pclose(fp);
    return out;
}

static std::vector<int> listRunningUsers() {
    std::vector<int> users;
    std::istringstream iss(execCommand("pm list users"));
    std::string line;
    const std::regex userInfoRegex(R"(UserInfo\{(\d+):)");
    std::smatch match;
    while (std::getline(iss, line)) {
        if (line.find("running") == std::string::npos) continue;
        if (std::regex_search(line, match, userInfoRegex)) {
            try { users.push_back(std::stoi(match.str(1))); } catch (...) {}
        }
    }
    if (users.empty()) users.push_back(0);
    return users;
}

static void refreshPackageCache() {
    std::unordered_map<int, PkgEntry> fresh;
    for (int user : listRunningUsers()) {
        std::istringstream iss(execCommand("cmd package list packages -U --user " + std::to_string(user)));
        std::string line;
        while (std::getline(iss, line)) {
            if (line.rfind("package:", 0) != 0) continue;
            size_t uidPos = line.find(" uid:");
            if (uidPos == std::string::npos) continue;
            std::string name = line.substr(8, uidPos - 8);
            int uid = 0;
            try { uid = std::stoi(line.substr(uidPos + 5)); } catch (...) { continue; }
            // shared-uid cases: the first enumerated package wins deterministically
            fresh.emplace(uid, PkgEntry{name, user});
        }
    }
    g_uidToPkg = std::move(fresh);
    g_pkgCacheRefreshedAt = std::chrono::steady_clock::now();
}

static void maybeRefreshPackageCache() {
    const auto now = std::chrono::steady_clock::now();
    if (!g_uidToPkg.empty() &&
        std::chrono::duration<double>(now - g_pkgCacheRefreshedAt).count() < PKG_CACHE_TTL_SECONDS) {
        return;
    }
    refreshPackageCache();
}

// Lazily resolved base-apk path per "pkg:user"; lets the app parse label/icon
// straight out of the apk for packages installed only in another Android user
static std::unordered_map<std::string, std::string> g_apkPathCache;

static std::string resolveApkPath(const std::string &pkg, int user) {
    const std::string key = pkg + ":" + std::to_string(user);
    auto cached = g_apkPathCache.find(key);
    if (cached != g_apkPathCache.end()) return cached->second;

    static const std::regex safePkg("^[a-zA-Z0-9._]+$");
    if (!std::regex_match(pkg, safePkg) || pkg.length() > 255 || user < 0) return "";

    std::istringstream iss(execCommand("pm path --user " + std::to_string(user) + " " + pkg));
    std::string line;
    while (std::getline(iss, line)) {
        // The first package: line is the base apk of possibly-split apks
        if (line.rfind("package:", 0) != 0) continue;
        const std::string path = line.substr(8);
        if (!path.empty()) g_apkPathCache[key] = path;
        return path;
    }
    return "";
}


void processCommand(const std::string &received) {
    try {
        json j_in = json::parse(received);
        std::string cmd = j_in.value("cmd", "");
        json j_out;

        if (cmd == "PING") {
            j_out["type"] = "PONG";
            send_json(j_out);
        } else if (cmd == "KILL") {
            int pid = j_in.value("pid", -1);
            bool success = (pid > 0) && killProcess(pid);
            j_out["type"] = "KILL_RESULT";
            j_out["success"] = success;
            send_json(j_out);
        } else if (cmd == "FORCE_STOP") {
            std::string pkg = j_in.value("pkg", "");
            int user = j_in.value("user", -1);
            std::regex pkg_regex("^[a-zA-Z0-9._]+$");
            bool success = false;
            if (std::regex_match(pkg, pkg_regex) && pkg.length() <= 255) {
                std::string scmd = "am force-stop";
                if (user >= 0) scmd += " --user " + std::to_string(user);
                scmd += " " + pkg;
                success = (system(scmd.c_str()) == 0);
            }
            j_out["type"] = "KILL_RESULT";
            j_out["success"] = success;
            send_json(j_out);
        } else if (cmd == "KILL_GROUP") {
            int pgid = j_in.value("pgid", -1);
            bool success = (pgid > 0) ? killProcessGroup(pgid) : false;
            j_out["type"] = "KILL_RESULT";
            j_out["success"] = success;
            send_json(j_out);
        } else if (cmd == "STOP_SELF" || cmd == "BUSY") {
            keep_running = 0;
        } else if (cmd == "LIST_PROCESS") {
            maybeRefreshPackageCache();
            if (g_prevCpuSamples.empty()) seedCpuSamples();
            auto procs = collectProcs();
            computeInstantCpu(procs);
            json procs_j = json::array();
            for (const auto &p : procs) {
                json pj = procToJson(p);
                auto pkgIt = g_uidToPkg.find(p.uid);
                if (pkgIt != g_uidToPkg.end()) {
                    pj["pkg"] = pkgIt->second.name;
                    pj["pkgUser"] = pkgIt->second.userId;
                }
                procs_j.push_back(pj);
            }
            j_out["type"] = "PROCESS_LIST";
            j_out["processes"] = procs_j;
            send_json(j_out);
        } else if (cmd == "CPU_PING") {
            j_out["type"] = "CPU_USAGE";
            j_out["usage"] = calculateCpuUsage();
            send_json(j_out);
        } else if (cmd == "SWAP_PING") {
            long used, total;
            getSwapUsage(used, total);
            j_out["type"] = "SWAP_USAGE";
            j_out["used"] = used;
            j_out["total"] = total;
            send_json(j_out);
        } else if (cmd == "GPU_PING") {
            j_out["type"] = "GPU_USAGE";
            j_out["usage"] = calculateGpuUsage();
            send_json(j_out);
        } else if (cmd == "CTEMP_PING") {
            j_out["type"] = "CPU_TEMP";
            j_out["temp"] = getCpuTemperatureCelsius();
            send_json(j_out);
        } else if (cmd == "PING_PID_CPU") {
            int pid = j_in.value("pid", -1);
            float usage = 0.0f;
            auto cached = g_lastSubtreeCpuPct.find(pid);
            if (cached != g_lastSubtreeCpuPct.end()) {
                usage = cached->second;
            } else {
                usage = measureProcessCpuInstant(pid, 200);
            }
            j_out["type"] = "PROCESS_CPU_USAGE";
            j_out["usage"] = usage;
            send_json(j_out);
        } else if (cmd == "PKG_APK_PATH") {
            const std::string pkg = j_in.value("pkg", "");
            const int user = j_in.value("user", -1);
            j_out["type"] = "PKG_APK_PATH";
            j_out["pkg"] = pkg;
            j_out["path"] = resolveApkPath(pkg, user);
            send_json(j_out);
        } else if (cmd == "BATTERY_PING") {
            json battery = json::object();
            getBatteryInfo(battery);
            j_out["type"] = "BATTERY_INFO";
            j_out["battery"] = battery;
            send_json(j_out);
        } else if (cmd == "LIST_NET_INTERFACES") {
            auto interfaces = listNetInterfaces();
            json interfaces_j = json::array();
            for (const auto& iface : interfaces) {
                interfaces_j.push_back({{"name", iface.name}, {"totalBytes", iface.totalBytes}});
            }
            j_out["type"] = "NET_INTERFACE_LIST";
            j_out["interfaces"] = interfaces_j;
            send_json(j_out);
        } else if (cmd == "NET_PING") {
            std::string iface = j_in.value("interface", "");
            auto now = std::chrono::steady_clock::now();
            auto curr = getNetStat(iface);

            j_out["type"] = "NET_STATS";

            auto it = netStatCache.find(iface);
            if (it != netStatCache.end()) {
                auto& prev = it->second;
                double elapsed = std::chrono::duration<double>(now - prev.timestamp).count();

                if (elapsed > 0.0) {
                    j_out["rxBytesPerSec"] = (curr.rxBytes - prev.rxBytes) / elapsed;
                    j_out["txBytesPerSec"] = (curr.txBytes - prev.txBytes) / elapsed;
                } else {
                    j_out["rxBytesPerSec"] = 0;
                    j_out["txBytesPerSec"] = 0;
                }
            } else {
                j_out["rxBytesPerSec"] = 0;
                j_out["txBytesPerSec"] = 0;
            }

            netStatCache[iface] = {curr.rxBytes, curr.txBytes, now};

            j_out["rxBytes"] = curr.rxBytes;
            j_out["txBytes"] = curr.txBytes;
            send_json(j_out);
        } else {
            log_line("Unknown command: " + cmd);
        }
    } catch (const std::exception& e) {
        log_line("JSON parse error: " + std::string(e.what()) + " | Data: " + received);
    }
}

int main() {
    signal(SIGINT, handle_sigint);
    signal(SIGTERM, handle_sigint);
    signal(SIGPIPE, SIG_IGN);

    const size_t BUF_SIZE = 8192;
    std::unique_ptr<char[]> buf(new char[BUF_SIZE]);
    std::string recv_buffer;

    while (keep_running) {
        ssize_t r = read(STDIN_FILENO, buf.get(), BUF_SIZE - 1);
        if (r > 0) {
            buf[r] = '\0';
            recv_buffer.append(buf.get(), r);
            size_t pos;
            while ((pos = recv_buffer.find('\n')) != std::string::npos) {
                std::string message = recv_buffer.substr(0, pos);
                recv_buffer.erase(0, pos + 1);
                if (!message.empty()) processCommand(message);
            }
        } else if (r == 0) {
            break;
        } else {
            if (errno == EINTR) continue;
            break;
        }
    }

    return 0;
}
