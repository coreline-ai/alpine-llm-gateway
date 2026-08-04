#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint mobile_agent_auth.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'mobile_agent_auth'
  s.version          = '0.0.1'
  s.summary          = 'Native OAuth/OIDC session broker for MobileAgent.'
  s.description      = <<-DESC
Native AppAuth session and Keychain storage for the MobileAgent Flutter app.
                       DESC
  s.homepage         = 'https://github.com/coreline-ai/flutter_mobile_agent'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Coreline AI' => 'opensource@coreline.ai' }
  s.source           = { :path => '.' }
  s.source_files = 'mobile_agent_auth/Sources/mobile_agent_auth/**/*'
  s.dependency 'Flutter'
  s.dependency 'AppAuth', '~> 2.1'
  s.platform = :ios, '13.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'

  # If your plugin requires a privacy manifest, for example if it uses any
  # required reason APIs, update the PrivacyInfo.xcprivacy file to describe your
  # plugin's privacy impact, and then uncomment this line. For more information,
  # see https://developer.apple.com/documentation/bundleresources/privacy_manifest_files
  # s.resource_bundles = {'mobile_agent_auth_privacy' => ['mobile_agent_auth/Sources/mobile_agent_auth/PrivacyInfo.xcprivacy']}
end
