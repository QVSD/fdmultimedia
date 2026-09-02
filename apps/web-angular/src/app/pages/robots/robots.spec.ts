import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Robots } from './robots';

describe('Robots', () => {
  let component: Robots;
  let fixture: ComponentFixture<Robots>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Robots],
    }).compileComponents();

    fixture = TestBed.createComponent(Robots);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
