import 'dart:io';

import 'package:app_installer/app_installer.dart';
import 'package:collection/collection.dart';
import 'package:cr_file_saver/file_saver.dart';
import 'package:device_apps/device_apps.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:injectable/injectable.dart';
import 'package:path_provider/path_provider.dart';
import 'package:revanced_manager/app/app.locator.dart';
import 'package:revanced_manager/models/patch.dart';
import 'package:revanced_manager/models/patched_application.dart';
import 'package:revanced_manager/services/manager_api.dart';
import 'package:revanced_manager/services/root_api.dart';
import 'package:share_extend/share_extend.dart';

@lazySingleton
class PatcherAPI {
  static const patcherChannel =
      MethodChannel('app.revanced.manager.flutter/patcher');
  final ManagerAPI _managerAPI = locator<ManagerAPI>();
  final RootAPI _rootAPI = RootAPI();
  late Directory _dataDir;
  late Directory _tmpDir;
  late File _keyStoreFile;
  File? _jarPatchBundleFile;
  File? _outFile;

  Future<void> initialize() async {
    final Directory appCache = await getTemporaryDirectory();
    _dataDir = await getExternalStorageDirectory() ?? appCache;
    _tmpDir = Directory('${appCache.path}/patcher');
    _keyStoreFile = File('${_dataDir.path}/revanced-manager.keystore');
    cleanPatcher();
  }

  void cleanPatcher() {
    if (_tmpDir.existsSync()) {
      _tmpDir.deleteSync(recursive: true);
    }
  }

  Future<bool> loadPatches() async {
    if (_jarPatchBundleFile == null) {
      _jarPatchBundleFile = await _managerAPI.downloadPatches();
      if (_jarPatchBundleFile != null) {
        try {
          await patcherChannel.invokeMethod<bool>(
            'loadPatches',
            {
              'jarPatchBundlePath': _jarPatchBundleFile!.path,
              'cacheDirPath': _tmpDir!.path,
            },
          );
        } on Exception {
          return false;
        }
      }
    }
    return _jarPatchBundleFile != null;
  }

  Future<List<ApplicationWithIcon>> getFilteredInstalledApps() async {
    List<ApplicationWithIcon> filteredPackages = [];
    bool isLoaded = await loadPatches();
    if (isLoaded) {
      try {
        List<String>? patchesPackages = await patcherChannel
            .invokeListMethod<String>('getCompatiblePackages');
        if (patchesPackages != null) {
          for (String package in patchesPackages) {
            try {
              ApplicationWithIcon? app = await DeviceApps.getApp(package, true)
              as ApplicationWithIcon?;
              if (app != null) {
                filteredPackages.add(app);
              }
            } catch (e) {
              continue;
            }
          }
        }
      } on Exception {
        return List.empty();
      }
    }
    return filteredPackages;
  }

  Future<List<Patch>> getFilteredPatches(
      PatchedApplication? selectedApp,
      ) async {
    List<Patch> filteredPatches = [];
    if (selectedApp != null) {
      bool isLoaded = await loadPatches();
      if (isLoaded) {
        try {
          var patches =
          await patcherChannel.invokeListMethod<Map<dynamic, dynamic>>(
            'getFilteredPatches',
            {
              'targetPackage': selectedApp.packageName,
              'targetVersion': selectedApp.version,
              'ignoreVersion': true,
            },
          );
          if (patches != null) {
            for (var patch in patches) {
              if (!filteredPatches
                  .any((element) => element.name == patch['name'])) {
                filteredPatches.add(
                  Patch(
                    name: patch['name'],
                    version: patch['version'] ?? '?.?.?',
                    description: patch['description'] ?? 'N/A',
                    include: patch['include'] ?? true,
                  ),
                );
              }
            }
          }
        } on Exception {
          return List.empty();
        }
      }
    }
    return filteredPatches;
  }

  Future<List<Patch>> getAppliedPatches(
      PatchedApplication? selectedApp,
      ) async {
    List<Patch> appliedPatches = [];
    if (selectedApp != null) {
      bool isLoaded = await loadPatches();
      if (isLoaded) {
        try {
          var patches =
          await patcherChannel.invokeListMethod<Map<dynamic, dynamic>>(
            'getFilteredPatches',
            {
              'targetPackage': selectedApp.packageName,
              'targetVersion': selectedApp.version,
              'ignoreVersion': true,
            },
          );
          if (patches != null) {
            for (var patch in patches) {
              if (selectedApp.appliedPatches.contains(patch['name'])) {
                appliedPatches.add(
                  Patch(
                    name: patch['name'],
                    version: patch['version'] ?? '?.?.?',
                    description: patch['description'] ?? 'N/A',
                    include: patch['include'] ?? true,
                  ),
                );
              }
            }
          }
        } on Exception {
          return List.empty();
        }
      }
    }
    return appliedPatches;
  }

  Future<bool> needsResourcePatching(
    List<Patch> selectedPatches, String packageName
  ) async {
    return await patcherChannel.invokeMethod<bool>(
      'needsResourcePatching',
      {
        'selectedPatches': selectedPatches.map((e) => e.name),
        'packageName': packageName
      },
    ) ?? false;
  }

  Future<String> getOriginalFilePath(
    String packageName,
    String originalFilePath,
  ) async {
    try {
      final bool hasRootPermissions = await _rootAPI.hasRootPermissions();
      if (hasRootPermissions) {
        originalFilePath = await _rootAPI.getOriginalFilePath(
          packageName,
          originalFilePath,
        );
      }
      return originalFilePath;
    } on Exception catch (e) {
      if (kDebugMode) {
        print(e);
      }
      return originalFilePath;
    }
  }

  Future<void> runPatcher(
    String packageName,
    String originalFilePath,
    List<Patch> selectedPatches,
  ) async {
    final File? patchBundleFile = await _managerAPI.downloadPatches();
    final File? integrationsFile = await _managerAPI.downloadIntegrations();
    if (patchBundleFile != null) {
      _dataDir.createSync();
      _tmpDir.createSync();
      final Directory workDir = _tmpDir.createTempSync('tmp-');
      final File inputFile = File('${workDir.path}/base.apk');
      final File patchedFile = File('${workDir.path}/patched.apk');
      _outFile = File('${workDir.path}/out.apk');
      final Directory cacheDir = Directory('${workDir.path}/cache');
      cacheDir.createSync();
      try {
        await patcherChannel.invokeMethod(
          'runPatcher',
          {
            'patchBundleFilePath': patchBundleFile.path,
            'originalFilePath': await getOriginalFilePath(
              packageName,
              originalFilePath,
            ),
            'inputFilePath': inputFile.path,
            'patchedFilePath': patchedFile.path,
            'outFilePath': _outFile!.path,
            'integrationsPath': integrationsFile!.path,
            'selectedPatches': selectedPatches.map((p) => p.name).toList(),
            'cacheDirPath': cacheDir.path,
            'keyStoreFilePath': _keyStoreFile.path,
            'keystorePassword': _managerAPI.getKeystorePassword(),
          },
        );
      } on Exception catch (e) {
        if (kDebugMode) {
          print(e);
        }
      }
    }
  }

  Future<bool> installPatchedFile(PatchedApplication patchedApp) async {
    if (_outFile != null) {
      try {
        if (patchedApp.isRooted) {
          final bool hasRootPermissions = await _rootAPI.hasRootPermissions();
          if (hasRootPermissions) {
            return _rootAPI.installApp(
              patchedApp.packageName,
              patchedApp.apkFilePath,
              _outFile!.path,
            );
          }
        } else {
          await AppInstaller.installApk(_outFile!.path);
          return await DeviceApps.isAppInstalled(
            patchedApp.packageName,
          );
        }
      } on Exception catch (e) {
        if (kDebugMode) {
          print(e);
        }
        return false;
      }
    }
    return false;
  }

  void exportPatchedFile(String appName, String version) {
    try {
      if (_outFile != null) {
        final String newName = _getFileName(appName, version);
        CRFileSaver.saveFileWithDialog(
          SaveFileDialogParams(
            sourceFilePath: _outFile!.path,
            destinationFileName: newName,
          ),
        );
      }
    } on Exception catch (e) {
      if (kDebugMode) {
        print(e);
      }
    }
  }

  void sharePatchedFile(String appName, String version) {
    try {
      if (_outFile != null) {
        final String newName = _getFileName(appName, version);
        final int lastSeparator = _outFile!.path.lastIndexOf('/');
        final String newPath =
            _outFile!.path.substring(0, lastSeparator + 1) + newName;
        final File shareFile = _outFile!.copySync(newPath);
        ShareExtend.share(shareFile.path, 'file');
      }
    } on Exception catch (e) {
      if (kDebugMode) {
        print(e);
      }
    }
  }

  String _getFileName(String appName, String version) {
    final String prefix = appName.toLowerCase().replaceAll(' ', '-');
    final String newName = '$prefix-revanced_v$version.apk';
    return newName;
  }

  Future<void> sharePatcherLog(String logs) async {
    final Directory appCache = await getTemporaryDirectory();
    final Directory logDir = Directory('${appCache.path}/logs');
    logDir.createSync();
    final String dateTime = DateTime.now()
        .toIso8601String()
        .replaceAll('-', '')
        .replaceAll(':', '')
        .replaceAll('T', '')
        .replaceAll('.', '');
    final File log =
        File('${logDir.path}/revanced-manager_patcher_$dateTime.log');
    log.writeAsStringSync(logs);
    ShareExtend.share(log.path, 'file');
  }

  String getRecommendedVersion(String packageName) {
    final Map<String, int> versions = {};
    for (final Patch patch in _patches) {
      final Package? package = patch.compatiblePackages.firstWhereOrNull(
        (pack) => pack.name == packageName,
      );
      if (package != null) {
        for (final String version in package.versions) {
          versions.update(
            version,
            (value) => versions[version]! + 1,
            ifAbsent: () => 1,
          );
        }
      }
    }
    if (versions.isNotEmpty) {
      final entries = versions.entries.toList()
        ..sort((a, b) => a.value.compareTo(b.value));
      versions
        ..clear()
        ..addEntries(entries);
      versions.removeWhere((key, value) => value != versions.values.last);
      return (versions.keys.toList()..sort()).last;
    }
    return '';
  }
}
