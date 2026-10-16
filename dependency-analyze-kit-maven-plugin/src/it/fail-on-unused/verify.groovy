import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def reportFile = new File(basedir, 'target/dependency-analyze-kit/report.json')
assert reportFile.isFile() : "report.json not found: ${reportFile}"
def report = new JsonSlurper().parse(reportFile)
def pretty = JsonOutput.prettyPrint(reportFile.text)

def dep = { String key ->
    def d = report.dependencies.find { it.dependency == key }
    assert d != null : "dependency ${key} not in report:\n${pretty}"
    d
}
def verdict = { String key, String expected ->
    def d = dep(key)
    assert d.verdict == expected : "${key}: expected ${expected} but was ${d.verdict} (${d.summary})\n${pretty}"
}

def log = new File(basedir, 'build.log')
assert log.isFile() : "build.log not found: ${log}"
assert log.text.contains('1 unused dependency candidate(s)') : "build.log lacks '1 unused dependency candidate(s)'"

verdict('commons-io:commons-io', 'UNUSED_CANDIDATE')
verdict('org.apache.commons:commons-lang3', 'IGNORED')
assert dep('org.apache.commons:commons-lang3').summary.startsWith('ignored by configuration') : "unexpected summary\n${pretty}"
verdict('org.springframework.boot:spring-boot-starter', 'USED_BYTECODE')

return true
