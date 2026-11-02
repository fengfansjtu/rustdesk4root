import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:flutter_hbb/common/widgets/android_touch_region.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  Future<void> mount(WidgetTester tester, List<String> events,
      {GestureScaleUpdateCallback? onScale, Future<void>? beforeDown}) async {
    await tester.pumpWidget(Directionality(
      textDirection: TextDirection.ltr,
      child: AndroidTouchRegion(
        canStart: (_) => true,
        onDown: (p) async {
          if (beforeDown != null) await beforeDown;
          events.add('down $p');
        },
        onMove: (p) async => events.add('move $p'),
        onUp: () async => events.add('up'),
        onScaleUpdate: onScale ?? (_) {},
        onScaleEnd: (_) {},
        child: const ColoredBox(color: Color(0xff000000)),
      ),
    ));
  }

  testWidgets('rapid consecutive swipes each deliver a complete stroke',
      (tester) async {
    final events = <String>[];
    final pending = Completer<void>();
    await mount(tester, events, beforeDown: pending.future);
    for (var pointer = 1; pointer <= 2; pointer++) {
      final finger =
          await tester.startGesture(const Offset(100, 100), pointer: pointer);
      await finger.moveTo(const Offset(100, 160));
      await finger.up();
      await tester.pump();
    }
    expect(events, isEmpty);
    pending.complete();
    await tester.pump();
    expect(events, [
      'down Offset(100.0, 100.0)',
      'move Offset(100.0, 160.0)',
      'up',
      'down Offset(100.0, 100.0)',
      'move Offset(100.0, 160.0)',
      'up',
    ]);
  });

  testWidgets('tap and hold send one press each without synthetic clicks',
      (tester) async {
    final events = <String>[];
    await mount(tester, events);
    await tester.tapAt(const Offset(100, 100));
    final hold = await tester.startGesture(const Offset(150, 150));
    await tester.pump(const Duration(milliseconds: 600));
    expect(events.where((e) => e.startsWith('down')), hasLength(2));
    expect(events.where((e) => e == 'up'), hasLength(1));
    await hold.up();
    await tester.pump(const Duration(milliseconds: 300));
    expect(events.where((e) => e.startsWith('down')), hasLength(2));
    expect(events.where((e) => e == 'up'), hasLength(2));
  });

  testWidgets(
      'cancel, pinch and disposal release without resuming a held finger',
      (tester) async {
    final events = <String>[];
    var scaleUpdates = 0;
    await mount(tester, events, onScale: (_) => scaleUpdates++);
    final cancelled = await tester.startGesture(const Offset(100, 100));
    await cancelled.cancel();
    final first = await tester.startGesture(const Offset(100, 100), pointer: 2);
    final second =
        await tester.startGesture(const Offset(200, 100), pointer: 3);
    await first.moveTo(const Offset(50, 100));
    await second.moveTo(const Offset(250, 100));
    await second.up();
    await first.moveTo(const Offset(50, 200));
    await first.up();
    await tester.pump();
    expect(scaleUpdates, greaterThan(0));
    expect(events, [
      'down Offset(100.0, 100.0)',
      'up',
      'down Offset(100.0, 100.0)',
      'up',
    ]);
    await tester.startGesture(const Offset(120, 120), pointer: 4);
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    expect(events.last, 'up');
    expect(events.where((e) => e == 'up'), hasLength(3));
  });
}
