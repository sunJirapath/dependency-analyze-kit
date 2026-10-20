import groovy.json.JsonSlurper

def report = new JsonSlurper().parse(new File(basedir, 'target/dependency-analyze-kit/report.json'))
assert report.probeReports > 0
assert report.dependencies.find { it.dependency == 'io.github.sunjirapath.it:unconditional-starter' }.verdict == 'USED_AUTOCONFIG' : report
def contexts = new File(basedir, 'target/dependency-analyze-kit').listFiles().findAll { it.name.startsWith('context-') }.collect { new JsonSlurper().parse(it) }
assert contexts.any { it.fullAutoConfig && it.autoConfigs.count { ac -> ac.name == 'it.unconditional.FeatureAutoConfiguration' && ac.matched } == 1 }
return true
